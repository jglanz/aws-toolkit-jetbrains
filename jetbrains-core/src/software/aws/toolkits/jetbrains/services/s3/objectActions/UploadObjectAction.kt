// Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.aws.toolkits.jetbrains.services.s3.objectActions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.launch
import software.aws.toolkits.jetbrains.core.utils.getRequiredData
import software.aws.toolkits.jetbrains.services.s3.editor.S3EditorDataKeys
import software.aws.toolkits.jetbrains.services.s3.editor.S3TreeDirectoryNode
import software.aws.toolkits.jetbrains.services.s3.editor.S3TreeNode
import software.aws.toolkits.jetbrains.services.s3.editor.S3TreeTable
import software.aws.toolkits.jetbrains.utils.ApplicationThreadPoolScope
import software.aws.toolkits.jetbrains.utils.notifyError
import software.aws.toolkits.resources.message
import software.aws.toolkits.telemetry.Result
import software.aws.toolkits.telemetry.S3Telemetry

class UploadObjectAction : S3ObjectAction(message("s3.upload.object.action"), AllIcons.Actions.Upload) {
    override fun performAction(dataContext: DataContext, nodes: List<S3TreeNode>) {
        val project = dataContext.getRequiredData(CommonDataKeys.PROJECT)
        val treeTable = dataContext.getRequiredData(S3EditorDataKeys.BUCKET_TABLE)
        val node = nodes.firstOrNull() ?: treeTable.rootNode

        val descriptor =
            FileChooserDescriptorFactory.createAllButJarContentsDescriptor().withDescription(message("s3.upload.object.action", treeTable.bucket.name))
        val chooserDialog = FileChooserFactory.getInstance().createFileChooser(descriptor, project, null)
        val filesChosen = chooserDialog.choose(project).toList()

        // If there are no files chosen, the user has cancelled upload
        if (filesChosen.isEmpty()) {
            S3Telemetry.uploadObjects(project, Result.Cancelled)
            return
        }

        uploadObjects(project, treeTable, filesChosen, node)
    }

    override fun enabled(nodes: List<S3TreeNode>): Boolean = nodes.isEmpty() ||
        (nodes.size == 1 && nodes.first().let { it is S3TreeDirectoryNode })
}

fun uploadObjects(project: Project, treeTable: S3TreeTable, files: List<VirtualFile>, parentNode: S3TreeNode) {
    if (files.isEmpty()) {
        return
    }
    ApplicationThreadPoolScope("UploadObjectAction").launch {
        var changeMade = false
        try {
            files.forEach {
                if (it.isDirectory) {
                    notifyError(
                        title = message("s3.upload.object.failed", it.name),
                        content = message("s3.upload.directory.impossible", it.name),
                        project = project
                    )
                } else {
                    try {
                        treeTable.bucket.upload(project, it.inputStream, it.length, parentNode.directoryPath() + it.name)
                        changeMade = true
                    } catch (e: Exception) {
                        e.notifyError(message("s3.upload.object.failed", it.path), project)
                        throw e
                    }
                }
            }

            S3Telemetry.uploadObjects(project, Result.Succeeded, files.size.toDouble())
        } catch (e: Exception) {
            S3Telemetry.uploadObjects(project, Result.Failed, files.size.toDouble())
        } finally {
            if (changeMade) {
                treeTable.invalidateLevel(parentNode)
                treeTable.refresh()
            }
        }
    }
}
