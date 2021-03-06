// Copyright 2021 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.lambda.nodejs

import com.google.common.collect.BiMap
import com.google.common.collect.HashBiMap
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.javascript.debugger.LocalFileSystemFileFinder
import com.intellij.javascript.debugger.RemoteDebuggingFileFinder
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugProcessStarter
import com.intellij.xdebugger.XDebugSession
import com.jetbrains.debugger.wip.WipLocalVmConnection
import com.jetbrains.nodeJs.NodeChromeDebugProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.io.LocalFileFinder
import software.aws.toolkits.jetbrains.services.PathMapping
import software.aws.toolkits.jetbrains.services.lambda.execution.sam.SamRunningState
import java.net.InetSocketAddress

object NodeJsDebugUtils {
    private const val NODE_MODULES = "node_modules"

    suspend fun createDebugProcess(
        environment: ExecutionEnvironment,
        state: SamRunningState,
        debugHost: String,
        debugPorts: List<Int>
    ): XDebugProcessStarter {
        val executionResult = withContext(Dispatchers.IO) {
            // needs to run off EDT since it resolves credentials
            state.execute(environment.executor, environment.runner)
        }

        return object : XDebugProcessStarter() {
            override fun start(session: XDebugSession): XDebugProcess {
                val mappings = createBiMapMappings(state.pathMappings)
                val fileFinder = RemoteDebuggingFileFinder(mappings, LocalFileSystemFileFinder())

                val connection = WipLocalVmConnection()

                val process = NodeChromeDebugProcess(session, fileFinder, connection, executionResult)

                val processHandler = executionResult.processHandler
                val socketAddress = InetSocketAddress(debugHost, debugPorts.first())

                if (processHandler == null || processHandler.isStartNotified) {
                    connection.open(socketAddress)
                } else {
                    processHandler.addProcessListener(
                        object : ProcessAdapter() {
                            override fun startNotified(event: ProcessEvent) {
                                connection.open(socketAddress)
                            }
                        }
                    )
                }
                return process
            }
        }
    }

    /**
     * Convert [PathMapping] to NodeJs debugger path mapping format.
     *
     * Docker uses the same project structure for dependencies in the folder node_modules. We map the source code and
     * the dependencies in node_modules folder separately as the node_modules might not exist in the local project.
     */
    private fun createBiMapMappings(pathMapping: List<PathMapping>): BiMap<String, VirtualFile> {
        val mappings = HashBiMap.create<String, VirtualFile>(pathMapping.size)

        listOf(".", NODE_MODULES).forEach { subPath ->
            pathMapping.forEach {
                val remotePath = FileUtil.toCanonicalPath("${it.remoteRoot}/$subPath")
                LocalFileFinder.findFile("${it.localRoot}/$subPath")?.let { localFile ->
                    mappings.putIfAbsent("file://$remotePath", localFile)
                }
            }
        }

        return mappings
    }
}
