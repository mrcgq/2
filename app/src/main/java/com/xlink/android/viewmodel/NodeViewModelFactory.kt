package com.xlink.android.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.xlink.android.data.store.NodeStore
import com.xlink.android.data.sub.SubRepository

class NodeViewModelFactory(
    private val nodeStore: NodeStore,
    private val subRepository: SubRepository,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(NodeViewModel::class.java)) {
            return NodeViewModel(nodeStore, subRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
