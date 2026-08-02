package io.engage.sdk.messagecenter.divkit.render

import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope

internal class InboxAdapter(
    private val scope: CoroutineScope,
    private val actionRouter: InboxActionRouter,
) : ListAdapter<InboxUiItem, InboxAdapter.ViewHolder>(DIFF) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(InboxDivKitView(parent.context, scope, actionRouter))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.view.bind(getItem(position))
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.view.recycle()
        super.onViewRecycled(holder)
    }

    internal class ViewHolder(val view: InboxDivKitView) : RecyclerView.ViewHolder(view) {
        init {
            view.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<InboxUiItem>() {
            override fun areItemsTheSame(oldItem: InboxUiItem, newItem: InboxUiItem): Boolean =
                oldItem.entry.id == newItem.entry.id

            override fun areContentsTheSame(oldItem: InboxUiItem, newItem: InboxUiItem): Boolean =
                oldItem == newItem
        }
    }
}
