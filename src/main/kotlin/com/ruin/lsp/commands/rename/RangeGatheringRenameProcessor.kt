package com.ruin.lsp.commands.rename

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.TextRange
import com.intellij.psi.JavaTokenType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.meta.PsiMetaOwner
import com.intellij.psi.meta.PsiWritableMetaData
import com.intellij.refactoring.rename.BindablePsiReference
import com.intellij.refactoring.rename.PsiElementRenameHandler
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.usageView.UsageInfo
import com.intellij.util.IncorrectOperationException
import com.ruin.lsp.commands.find.toRange
import com.ruin.lsp.util.getDocument

class RangeGatheringRenameProcessor(proj: Project, val element: PsiElement, val name: String, editor: Editor)
    : RenameProcessor(proj,
    substituteElementToRename(element, editor) ?: element,
    name, false, false) {
    val refs: MutableList<RenameRange> = mutableListOf()

    override fun doRun() {
        val usagesRef: Ref<Array<UsageInfo>> = Ref()
        prepareRenaming(element, name, myAllRenames)
        usagesRef.set(DumbService.getInstance(myProject).runReadActionInSmartMode<Array<UsageInfo>> { findUsages() })
        if (!preprocessUsages(usagesRef)) return
        val newUsages = usagesRef.get()!!

        val classified = classifyUsages(myAllRenames.keys, newUsages)
        for (element in myAllRenames.keys) {
            val newName = myAllRenames[element] ?: continue

            val infos = classified.get(element)
            try {
                val toAdd = getRenames(element, newName, infos.toTypedArray())
                refs.addAll(toAdd)
            } catch (e: IncorrectOperationException) {
            }
        }
    }
}

private fun substituteElementToRename(element: PsiElement?, editor: Editor?): PsiElement? {
    if (element == null) return null
    val processor = RenamePsiElementProcessor.forElement(element)
    val substituted = processor.substituteElementToRename(element, editor)
    return if (substituted == null || !PsiElementRenameHandler.canRename(element.project, editor, substituted)) null else substituted
}

private fun canRenameElement(namedElement: PsiElement): Boolean {
    var writableMetaData: PsiWritableMetaData? = null
    if (namedElement is PsiMetaOwner) {
        val metaData = (namedElement as PsiMetaOwner).metaData
        if (metaData is PsiWritableMetaData) {
            writableMetaData = metaData
        }
    }
    return writableMetaData != null || namedElement is PsiNamedElement
}

private fun getRenames(namedElement: PsiElement, newName: String, usages: Array<UsageInfo>): List<RenameRange> {
    if (!canRenameElement(namedElement)) {
        LOG.error("Unknown element type:" + namedElement)
        return listOf()
    }

    // Rename the element itself.
    // NEEDS TO BE IDENTIFIER
    val elementRename = RenameRange(namedElement.textRange, namedElement.containingFile, newName)

    val doc = getDocument(namedElement.containingFile)!!
    val theRange = namedElement.textRange.toRange(doc)
    // Rename the places where the element is referenced.
    val usageRenames = getRenamesOfReferences(usages, newName)

    return usageRenames.plus(elementRename)
}

private fun getRenamesOfReferences(usages: Array<UsageInfo>, newName: String): List<RenameRange> =
    usages.mapNotNull { usage ->
        val ref = usage.reference ?: return@mapNotNull null
        if (ref !is BindablePsiReference) {
            RenameRange(ref.element.textRange, ref.element.containingFile, newName)
        } else
            null
    }


data class RenameRange(val textRange: TextRange, val file: PsiFile, val newName: String)
