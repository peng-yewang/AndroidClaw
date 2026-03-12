package com.androidclaw.app.engine

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.androidclaw.app.service.ClawAccessibilityService

/**
 * UI 节点查找工具类
 * 提供按 text / contentDescription / viewId / className 查找节点的能力
 */
object NodeFinder {

    private const val TAG = "NodeFinder"

    /**
     * 按文本查找节点（精确匹配）
     */
    fun findByText(text: String, root: AccessibilityNodeInfo? = null): List<AccessibilityNodeInfo> {
        val rootNode = root ?: ClawAccessibilityService.instance?.getRootNode() ?: return emptyList()
        val result = rootNode.findAccessibilityNodeInfosByText(text)
        return result?.toList() ?: emptyList()
    }

    /**
     * 获取所有节点
     */
    fun findAll(root: AccessibilityNodeInfo? = null): List<AccessibilityNodeInfo> {
        val rootNode = root ?: ClawAccessibilityService.instance?.getRootNode() ?: return emptyList()
        return traverseAndFilter(rootNode) { true }
    }

    /**
     * 按 View ID 查找节点
     */
    fun findByViewId(viewId: String, root: AccessibilityNodeInfo? = null): List<AccessibilityNodeInfo> {
        val rootNode = root ?: ClawAccessibilityService.instance?.getRootNode() ?: return emptyList()
        val result = rootNode.findAccessibilityNodeInfosByViewId(viewId)
        return result?.toList() ?: emptyList()
    }

    /**
     * 按 contentDescription 查找节点
     */
    fun findByDescription(desc: String, root: AccessibilityNodeInfo? = null): List<AccessibilityNodeInfo> {
        val rootNode = root ?: ClawAccessibilityService.instance?.getRootNode() ?: return emptyList()
        return traverseAndFilter(rootNode) { node ->
            node.contentDescription?.toString()?.contains(desc, ignoreCase = true) == true
        }
    }

    /**
     * 按 className 查找节点
     */
    fun findByClassName(className: String, root: AccessibilityNodeInfo? = null): List<AccessibilityNodeInfo> {
        val rootNode = root ?: ClawAccessibilityService.instance?.getRootNode() ?: return emptyList()
        return traverseAndFilter(rootNode) { node ->
            node.className?.toString()?.contains(className) == true
        }
    }

    /**
     * 按文本模糊搜索节点
     */
    fun findByTextContains(text: String, root: AccessibilityNodeInfo? = null): List<AccessibilityNodeInfo> {
        val rootNode = root ?: ClawAccessibilityService.instance?.getRootNode() ?: return emptyList()
        return traverseAndFilter(rootNode) { node ->
            node.text?.toString()?.contains(text, ignoreCase = true) == true
        }
    }

    /**
     * 查找可点击的父节点
     */
    fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) return current
            current = current.parent
        }
        return null
    }

    /**
     * 通用遍历 + 过滤
     */
    fun traverseAndFilter(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        traverseRecursive(root, predicate, result)
        return result
    }

    private fun traverseRecursive(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
        result: MutableList<AccessibilityNodeInfo>
    ) {
        try {
            if (predicate(node)) {
                result.add(node)
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                traverseRecursive(child, predicate, result)
            }
        } catch (e: Exception) {
            Log.w(TAG, "遍历节点异常: ${e.message}")
        }
    }

    /**
     * 打印节点树（调试用）
     */
    fun dumpNodeTree(node: AccessibilityNodeInfo?, depth: Int = 0): String {
        if (node == null) return ""
        val sb = StringBuilder()
        val indent = "  ".repeat(depth)
        sb.appendLine("${indent}[${node.className}] text='${node.text}' desc='${node.contentDescription}' id='${node.viewIdResourceName}' clickable=${node.isClickable}")
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            sb.append(dumpNodeTree(child, depth + 1))
        }
        return sb.toString()
    }
}
