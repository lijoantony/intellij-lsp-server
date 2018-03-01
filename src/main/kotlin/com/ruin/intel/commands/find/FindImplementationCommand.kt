package com.ruin.intel.commands.find

import com.github.kittinunf.result.Result
import com.intellij.codeInsight.TargetElementUtil
import com.intellij.psi.PsiElement
import com.ruin.intel.commands.Command
import com.ruin.intel.model.LanguageServerException
import com.ruin.intel.model.positionToOffset
import com.ruin.intel.values.Location
import com.ruin.intel.values.Position
import com.ruin.intel.values.TextDocumentIdentifier
import com.intellij.codeInsight.navigation.ImplementationSearcher
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.ruin.intel.Util.*


class FindImplementationCommand(val textDocumentIdentifier: TextDocumentIdentifier,
                                val position: Position) : Command<List<Location>> {
    override fun execute(project: Project, file: PsiFile): Result<List<Location>, Exception> {
        val doc = getDocument(file)
            ?: return Result.error(LanguageServerException("No implementations found."))

        val offset = positionToOffset(doc, position)
        val editor = createEditor(this, file, position.line, position.character)
        val element = findTargetElement(editor)

        val implementations = searchImplementations(editor, element, offset)

        // Disposer doesn't release editor after registering in createEditor?
        val editorFactory = EditorFactory.getInstance()
        editorFactory.releaseEditor(editor)

        val results = implementations?.map(::toLocation) ?: listOf()

        return Result.of(results)
    }
}

fun searchImplementations(editor: Editor, element: PsiElement?, offset: Int): Array<PsiElement>? {
    val targetElementUtil = TargetElementUtil.getInstance()
    val onRef = ReadAction.compute<Boolean, RuntimeException> {
        targetElementUtil.findTargetElement(editor,
            getFlags() and (TargetElementUtil.REFERENCED_ELEMENT_ACCEPTED or TargetElementUtil.LOOKUP_ITEM_ACCEPTED).inv(),
            offset) == null
    }
    val onRefValid = ReadAction.compute<Boolean, RuntimeException> {
        element == null || targetElementUtil.includeSelfInGotoImplementation(element)
    }
    return ImplementationSearcher().searchImplementations(element, editor, onRef && onRefValid, onRef)
}

fun getFlags() = TargetElementUtil.getInstance().definitionSearchFlags;