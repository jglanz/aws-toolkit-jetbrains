// Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.lambda.execution.sam

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ExpirableExecutor
import com.intellij.openapi.application.impl.coroutineDispatchingContext
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.util.registry.Registry
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.net.NetUtils
import com.intellij.xdebugger.XDebuggerManager
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.concurrency.AsyncPromise
import org.jetbrains.concurrency.Promise
import software.aws.toolkits.core.utils.error
import software.aws.toolkits.core.utils.getLogger
import software.aws.toolkits.jetbrains.utils.getCoroutineUiContext
import software.aws.toolkits.jetbrains.utils.notifyError
import software.aws.toolkits.resources.message

class SamDebugger(settings: LocalLambdaRunSettings) : SamRunner(settings) {
    private val debugExtension = resolveDebuggerSupport(settings)
    private val debugPorts = NetUtils.findAvailableSocketPorts(debugExtension.numberOfDebugPorts()).toList()
    private val edtContext = getCoroutineUiContext()

    override fun patchCommandLine(commandLine: GeneralCommandLine) {
        commandLine.addParameters(debugExtension.samArguments(debugPorts))
        debugPorts.forEach {
            commandLine.withParameters("--debug-port").withParameters(it.toString())
        }
    }

    override fun run(environment: ExecutionEnvironment, state: SamRunningState): Promise<RunContentDescriptor> {
        val promise = AsyncPromise<RunContentDescriptor>()
        val debuggerHeartbeatChannel = Channel<Unit>()
        val bgContext = ExpirableExecutor.on(AppExecutorUtil.getAppExecutorService()).expireWith(environment).coroutineDispatchingContext()

        // Submit task onto the application thread pool because ProgressManager does funky thread switching in real execution, but reuses
        // the current thread in tests, which may lead to tests being blocked for 1 minute per integration test
        ApplicationManager.getApplication().executeOnPooledThread {
            ProgressManager.getInstance().run(
                object : Task.Backgroundable(environment.project, message("lambda.debug.waiting"), false) {
                    override fun run(indicator: ProgressIndicator) {
                        var isDebuggerAttachDone = false

                        // spin until channel is closed (debugger attached or promise rejected), or it's been 60s since last SAM message
                        try {
                            runBlocking(bgContext) {
                                while (!isDebuggerAttachDone) {
                                    withTimeout(debuggerConnectTimeoutMs()) {
                                        LOG.trace("wait")
                                        debuggerHeartbeatChannel.receive()
                                        LOG.trace("recv")
                                    }
                                }
                            }
                        } catch (_: ClosedReceiveChannelException) {
                            // if promise was rejected, the error message will propagate through a different path
                            isDebuggerAttachDone = true
                        } catch (_: TimeoutCancellationException) {
                            LOG.warn("Timed out while waiting for debugger attacher result")
                        } catch (e: Exception) {
                            LOG.error("Received exception while waiting for debugger attacher result", e)
                        }

                        if (!isDebuggerAttachDone) {
                            val message = message("lambda.debug.attach.fail")
                            LOG.error { message }
                            notifyError(message("lambda.debug.attach.error"), message, environment.project)
                        }
                    }
                }
            )
        }

        val heartbeatFn: suspend () -> Unit = suspend {
            try {
                debuggerHeartbeatChannel.offer(Unit)
            } catch (_: ClosedSendChannelException) {
            }
        }

        try {
            resolveDebuggerSupport(state.settings).createDebugProcessAsync(environment, state, state.settings.debugHost, debugPorts, heartbeatFn)
                .onSuccess { debugProcessStarter ->
                    val debugManager = XDebuggerManager.getInstance(environment.project)
                    val runContentDescriptor = runBlocking(edtContext) {
                        if (debugProcessStarter == null) {
                            null
                        } else {
                            // Requires EDT on some paths, so always requires to be run on EDT
                            debugManager.startSession(environment, debugProcessStarter).runContentDescriptor
                        }
                    }
                    if (runContentDescriptor == null) {
                        promise.setError(IllegalStateException("Failed to create debug process"))
                    } else {
                        promise.setResult(runContentDescriptor)
                    }
                }
                .onError {
                    promise.setError(it)
                }
                .onProcessed {
                    // promise rejected or succeeded
                    debuggerHeartbeatChannel.close()
                }
        } catch (e: Exception) {
            // thrown from createDebugProcessAsync
            promise.setError(e)
        }

        return promise
    }

    private fun resolveDebuggerSupport(settings: LocalLambdaRunSettings) = when (settings) {
        is ImageTemplateRunSettings -> settings.imageDebugger
        is ZipSettings -> RuntimeDebugSupport.getInstance(settings.runtimeGroup)
        else -> throw IllegalStateException("Can't find debugger support for $settings")
    }

    companion object {
        private val LOG = getLogger<SamDebugger>()

        fun debuggerConnectTimeoutMs() = Registry.intValue("aws.debuggerAttach.timeout", 60000).toLong()
    }
}
