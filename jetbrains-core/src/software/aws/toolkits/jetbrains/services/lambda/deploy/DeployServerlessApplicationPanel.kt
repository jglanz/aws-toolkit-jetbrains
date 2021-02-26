// Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.aws.toolkits.jetbrains.services.lambda.deploy

import com.intellij.execution.util.EnvVariablesTable
import com.intellij.execution.util.EnvironmentVariable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.AnActionButton
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.panels.Wrapper
import software.aws.toolkits.jetbrains.services.cloudformation.Parameter
import software.aws.toolkits.jetbrains.services.ecr.resources.EcrResources.LIST_REPOS
import software.aws.toolkits.jetbrains.services.ecr.resources.Repository
import software.aws.toolkits.jetbrains.services.s3.resources.S3Resources.listBucketNamesByActiveRegion
import software.aws.toolkits.jetbrains.ui.ResourceSelector
import software.aws.toolkits.resources.message
import java.util.HashMap
import java.util.function.Consumer
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JTextField

class DeployServerlessApplicationPanel(private val project: Project) {
    lateinit var newStackName: JTextField
    lateinit var createS3BucketButton: JButton
    private lateinit var environmentVariablesTable: EnvVariablesTable
    lateinit var content: JPanel
    lateinit var s3Bucket: ResourceSelector<String>
    lateinit var stacks: ResourceSelector<Stack>
    lateinit var stackParameters: Wrapper
    lateinit var updateStack: JRadioButton
    lateinit var createStack: JRadioButton
    lateinit var requireReview: JCheckBox
    lateinit var parametersPanel: JPanel
    lateinit var useContainer: JCheckBox
    lateinit var capabilitiesPanel: JPanel
    lateinit var ecrRepo: ResourceSelector<Repository>
    lateinit var createEcrRepoButton: JButton
    private lateinit var ecrLabel: JLabel
    val capabilities: CapabilitiesEnumCheckBoxes = CapabilitiesEnumCheckBoxes()

    private fun createUIComponents() {
        environmentVariablesTable = EnvVariablesTable()
        stackParameters = Wrapper()
        stacks = ResourceSelector.builder()
            .resource(DeployServerlessApplicationDialog.ACTIVE_STACKS)
            .awsConnection(project)
            .build()
        s3Bucket = ResourceSelector.builder()
            .resource(listBucketNamesByActiveRegion(project))
            .awsConnection(project)
            .build()
        ecrRepo = ResourceSelector.builder()
            .resource(LIST_REPOS)
            .awsConnection(project)
            .customRenderer(SimpleListCellRenderer.create("", Repository::repositoryName))
            .build()
        if (!ApplicationManager.getApplication().isUnitTestMode) {
            val tableComponent = environmentVariablesTable.component
            hideActionButton(ToolbarDecorator.findAddButton(tableComponent))
            hideActionButton(ToolbarDecorator.findRemoveButton(tableComponent))
            hideActionButton(ToolbarDecorator.findEditButton(tableComponent))
            stackParameters.setContent(tableComponent)
        }
    }

    init {
        capabilities.checkboxes.forEach { capabilitiesPanel.add(it) }
        showImageOptions(false)
    }

    fun withTemplateParameters(parameters: List<Parameter>): DeployServerlessApplicationPanel {
        parametersPanel.border = IdeBorderFactory.createTitledBorder(message("serverless.application.deploy.template.parameters"), false)
        environmentVariablesTable.setValues(
            parameters.map { parameter: Parameter ->
                object : EnvironmentVariable(
                    parameter.logicalName,
                    parameter.defaultValue(),
                    false
                ) {
                    override fun getNameIsWriteable(): Boolean {
                        return false
                    }

                    override fun getDescription(): String? {
                        return parameter.description()
                    }
                }
            }.toList()
        )
        return this
    }

    val templateParameters: Map<String, String>
        get() {
            val parameters: MutableMap<String, String> = HashMap()
            environmentVariablesTable.stopEditing()
            environmentVariablesTable.environmentVariables
                .forEach(Consumer { envVar: EnvironmentVariable -> parameters[envVar.name] = envVar.value })
            return parameters
        }

    fun showImageOptions(hasImages: Boolean) {
        ecrLabel.isVisible = hasImages
        ecrRepo.isVisible = hasImages
        createEcrRepoButton.isVisible = hasImages
    }

    companion object {
        private fun hideActionButton(actionButton: AnActionButton?) {
            if (actionButton != null) {
                actionButton.isEnabled = false
                actionButton.isVisible = false
            }
        }
    }
}
