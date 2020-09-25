/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.worker;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.string.MoreStrings;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
public class WorkerProcess implements Closeable {

  private static final Logger LOG = Logger.get(WorkerProcess.class);

  private final ProcessExecutor executor;
  private final ProcessExecutorParams processParams;
  private final ProjectFilesystem filesystem;
  private final Path tmpPath;
  private final Path stdErr;
  private final AtomicInteger currentMessageID = new AtomicInteger();
  private boolean handshakePerformed = false;
  private final ConcurrentHashMap<Integer, SettableFuture<Integer>> commandExitCodes =
      new ConcurrentHashMap<>();
  @Nullable private WorkerProcessProtocol.CommandSender protocol;
  @Nullable private ProcessExecutor.LaunchedProcess launchedProcess;
  private final Thread readerThread;
  private final CountDownLatch readerShutdownSignal = new CountDownLatch(1);
  private final Thread watchdogThread;
  private final CountDownLatch watchdogShutdownSignal = new CountDownLatch(1);

  /**
   * Worker process is a process that stays alive and receives commands which describe jobs. Worker
   * processes may be combined into pools so they can perform different jobs concurrently. It
   * communicates via JSON stream and via files. Submitted job blocks the calling thread until it
   * receives the result back. Worker process must understand the protocol that Buck will use to
   * communicate with it.
   *
   * @param executor Process executor that will start worker process.
   * @param processParams Arguments for process executor.
   * @param filesystem File system for the worker process.
   * @param stdErr path where stderr of a process is kept
   * @param tmpPath Temp folder.
   */
  public WorkerProcess(
      ProcessExecutor executor,
      ProcessExecutorParams processParams,
      ProjectFilesystem filesystem,
      Path stdErr,
      Path tmpPath) {
    this.executor = executor;
    this.stdErr = stdErr;
    this.processParams =
        processParams.withRedirectError(ProcessBuilder.Redirect.to(stdErr.toFile()));
    this.filesystem = filesystem;
    this.tmpPath = tmpPath;
    this.readerThread = new Thread(this::readerLoop);
    this.readerThread.setDaemon(true);
    this.readerThread.setName(
        "Worker Process IO Thread: " + Joiner.on(' ').join(processParams.getCommand()));
    this.watchdogThread = new Thread(this::watchdogLoop);
    this.watchdogThread.setDaemon(true);
    this.watchdogThread.setName(
        "Worker Process Watchdog Thread: " + Joiner.on(' ').join(processParams.getCommand()));
  }

  public boolean isAlive() {
    return launchedProcess != null && launchedProcess.isAlive() && this.readerThread.isAlive();
  }

  public synchronized void ensureLaunchAndHandshake() throws IOException {
    if (handshakePerformed) {
      return;
    }
    LOG.debug(
        "Starting up process %d using command: '%s'",
        this.hashCode(), Joiner.on(' ').join(processParams.getCommand()));
    launchedProcess = executor.launchProcess(processParams);
    protocol =
        new WorkerProcessProtocolZero.CommandSender(
            launchedProcess.getStdin(),
            launchedProcess.getStdout(),
            stdErr,
            () -> {
              if (launchedProcess != null) {
                executor.destroyLaunchedProcess(launchedProcess);
              }
            },
            () -> launchedProcess != null && launchedProcess.isAlive());

    LOG.debug("Handshaking with process %d", this.hashCode());
    protocol.handshake(currentMessageID.getAndIncrement());
    this.readerThread.start();
    this.watchdogThread.start();
    handshakePerformed = true;
  }

  public ListenableFuture<WorkerJobResult> submitJob(String jobArgs) throws IOException {
    Preconditions.checkState(
        protocol != null,
        "Tried to submit a job to the worker process before the handshake was performed.");

    int messageID = currentMessageID.getAndAdd(1);
    Path argsPath = Paths.get(tmpPath.toString(), String.format("%d.args", messageID));
    Path stdoutPath = Paths.get(tmpPath.toString(), String.format("%d.out", messageID));
    Path stderrPath = Paths.get(tmpPath.toString(), String.format("%d.err", messageID));
    filesystem.deleteFileAtPathIfExists(stdoutPath);
    filesystem.deleteFileAtPathIfExists(stderrPath);
    filesystem.writeContentsToPath(jobArgs, argsPath);

    SettableFuture<Integer> exitCodeFuture = SettableFuture.create();
    commandExitCodes.put(messageID, exitCodeFuture);

    try {
      synchronized (this) {
        Preconditions.checkState(
            protocol != null && readerThread.isAlive(), "Worker job died somehow");

        LOG.debug(
            "Sending job %d to process %d \n" + " job arguments: '%s'",
            messageID, this.hashCode(), jobArgs);
        protocol.send(
            messageID, ImmutableWorkerProcessCommand.of(argsPath, stdoutPath, stderrPath));
      }
    } catch (Throwable t) {
      commandExitCodes.remove(messageID);
      throw t;
    }

    synchronized (commandExitCodes) {
      commandExitCodes.notify();
    }

    return exitCodeFuture.transform(
        (exitCode) -> {
          LOG.debug(
              "Receiving response for job %d from process %d - %d",
              messageID, this.hashCode(), exitCode);
          Optional<String> stdout = filesystem.readFileIfItExists(stdoutPath);
          Optional<String> stderr = filesystem.readFileIfItExists(stderrPath);
          LOG.debug(
              "Job %d for process %d finished \n"
                  + "  exit code: %d \n"
                  + "  stdout: %s \n"
                  + "  stderr: %s",
              messageID, this.hashCode(), exitCode, stdout.orElse(""), stderr.orElse(""));

          return WorkerJobResult.of(exitCode, stdout, stderr);
        },
        MoreExecutors.directExecutor());
  }

  @Override
  public synchronized void close() {
    LOG.debug("Closing process %d", this.hashCode());
    try {
      readerShutdownSignal.countDown();
      synchronized (commandExitCodes) {
        commandExitCodes.notifyAll();
      }
      if (protocol != null) {
        protocol.close();
      }
      readerThread.join(5000);
      Files.deleteIfExists(stdErr);
    } catch (Exception e) {
      LOG.debug(e, "Error closing worker process %s.", processParams.getCommand());

      LOG.debug("Worker process stderr at %s", this.stdErr.toString());

      try {
        String workerStderr =
            MoreStrings.truncatePretty(filesystem.readFileIfItExists(this.stdErr).orElse(""))
                .trim()
                .replace("\n", "\nstderr: ");
        LOG.error(
            "Worker process "
                + Joiner.on(' ').join(processParams.getCommand())
                + " failed. stderr: %s",
            workerStderr);
      } catch (Throwable t) {
        LOG.error(t, "Couldn't read stderr on failing close!");
      }

      throw new HumanReadableException(
          e,
          "Error while trying to close the worker process %s.",
          Joiner.on(' ').join(processParams.getCommand()));
    } finally {
      watchdogShutdownSignal.countDown();
    }
  }

  @VisibleForTesting
  void launchForTesting(WorkerProcessProtocol.CommandSender protocolMock) {
    this.protocol = protocolMock;
    this.readerThread.start();
    this.watchdogThread.start();
    handshakePerformed = true;
  }

  private void processNextCommandResponse() throws Throwable {
    Preconditions.checkState(
        protocol != null,
        "Tried to submit a job to the worker process before the handshake was performed.");

    WorkerProcessProtocol.CommandResponse commandResponse =
        this.protocol.receiveNextCommandResponse();
    SettableFuture<Integer> result = commandExitCodes.remove(commandResponse.getCommandId());
    Preconditions.checkState(
        result != null,
        "Received message id %d with no corresponding waiter! (result was %d)",
        commandResponse.getCommandId(),
        commandResponse.getExitCode());

    result.set(commandResponse.getExitCode());
  }

  private void readerLoop() {
    try {
      while (readerShutdownSignal.getCount() > 0) {
        // A dance here to avoid calling `processNextCommandResponse` if we're not waiting for any
        // commands. In the best case scenario, where we don't call `close()` until all commands are
        // done, this will avoid painful exceptions in `close()`.
        synchronized (commandExitCodes) {
          while (readerShutdownSignal.getCount() > 0 && commandExitCodes.isEmpty()) {
            commandExitCodes.wait();
          }
        }
        if (readerShutdownSignal.getCount() > 0 && !commandExitCodes.isEmpty()) {
          processNextCommandResponse();
        }
      }
    } catch (Throwable t) {
      // The WorkerProcessProtocol isn't really conducive to concurrency, so we just assume
      // that shutdowns will cause some kind of exception.
      // TODO(mikekap): Refactor the protocol enough to allow interrupting reads on a shutdown
      // signal.
      if (readerShutdownSignal.getCount() == 0) {
        return;
      }
      LOG.error(t, "Worker pool reader process failed");
      Throwables.throwIfUnchecked(t);
      throw new RuntimeException(t);
    }
  }

  private void watchdogLoop() {
    try {
      while (!watchdogShutdownSignal.await(5, TimeUnit.SECONDS)) {
        if (!readerThread.isAlive()) {
          failAllFutures();
        }
      }

      failAllFutures();
    } catch (Throwable t) {
      LOG.error(t, "Worker Process Watchdog thread error!");
      Throwables.throwIfUnchecked(t);
      throw new RuntimeException(t);
    } finally {
      // Kill it with fire.
      while (readerThread.isAlive()) {
        readerThread.interrupt();
      }
    }
  }

  private void failAllFutures() {
    HashSet<Integer> keys = new HashSet<>(commandExitCodes.keySet());
    for (Integer key : keys) {
      SettableFuture<Integer> result = commandExitCodes.remove(key);
      if (result != null) {
        result.setException(new RuntimeException("Worker process error"));
      }
    }
  }
}
