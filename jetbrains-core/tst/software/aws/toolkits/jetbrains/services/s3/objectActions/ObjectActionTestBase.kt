// Copyright 2021 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.s3.objectActions

import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.ProjectRule
import com.intellij.ui.treeStructure.treetable.TreeTableTree
import com.nhaarman.mockitokotlin2.mock
import org.junit.Before
import org.junit.Rule
import software.aws.toolkits.core.utils.test.aString
import software.aws.toolkits.jetbrains.services.s3.editor.S3EditorDataKeys
import software.aws.toolkits.jetbrains.services.s3.editor.S3TreeDirectoryNode
import software.aws.toolkits.jetbrains.services.s3.editor.S3TreeNode
import software.aws.toolkits.jetbrains.services.s3.editor.S3TreeTable
import software.aws.toolkits.jetbrains.services.s3.editor.S3TreeTableModel
import software.aws.toolkits.jetbrains.services.s3.editor.S3VirtualBucket

open class ObjectActionTestBase {
    @Rule
    @JvmField
    val projectRule = ProjectRule()

    protected val bucketName = aString()
    protected lateinit var treeTable: S3TreeTable
    protected lateinit var s3Bucket: S3VirtualBucket

    @Before
    fun setUp() {
        s3Bucket = mock {
            on { name }.thenReturn(bucketName)
        }
        val mockModel = mock<TreeTableTree> {
            on { model }.thenReturn(S3TreeTableModel(mock(), emptyArray(), mock()))
        }
        treeTable = mock {
            on { bucket }.thenReturn(s3Bucket)
            on { rootNode }.thenReturn(S3TreeDirectoryNode(s3Bucket, null, ""))
            on { tree }.thenReturn(mockModel)
        }
    }

    protected fun AnAction.executeAction(nodes: List<S3TreeNode>) {
        val event = createEventFor(this, nodes)
        actionPerformed(event)
    }

    protected fun AnAction.updateAction(nodes: List<S3TreeNode>): Presentation {
        val event = createEventFor(this, nodes)
        update(event)
        return event.presentation
    }

    private fun createEventFor(action: AnAction, nodes: List<S3TreeNode>): AnActionEvent {
        val projectContext = SimpleDataContext.getProjectContext(projectRule.project)
        val dc = SimpleDataContext.getSimpleContext(
            mapOf(
                S3EditorDataKeys.SELECTED_NODES.name to nodes,
                S3EditorDataKeys.BUCKET_TABLE.name to treeTable
            ),
            projectContext
        )
        return AnActionEvent.createFromAnAction(action, null, ActionPlaces.UNKNOWN, dc)
    }
}
