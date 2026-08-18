package io.engage.sdk.messagecenter.divkit.render

import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.engage.sdk.EngageLogger
import kotlinx.coroutines.CoroutineScope

internal class InboxAdapter(
    private val scope: CoroutineScope,
    private val actionRouter: InboxActionRouter,
    private val onOpenDetail: (InboxUiItem) -> Unit,
) : ListAdapter<InboxUiItem, InboxAdapter.ViewHolder>(DIFF) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(InboxDivKitView(parent.context, scope, actionRouter)).also {
            EngageLogger.verbose("MessageCenter.Adapter", "view holder created viewType=$viewType")
        }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        EngageLogger.verbose("MessageCenter.Adapter", "view holder binding position=$position entryId=${getItem(position).entry.id}")
        holder.view.bind(getItem(position))
        holder.view.setOnClickListener {
            val currentPosition = holder.bindingAdapterPosition
            if (currentPosition != RecyclerView.NO_POSITION) onOpenDetail(getItem(currentPosition))
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        EngageLogger.verbose("MessageCenter.Adapter", "view holder recycling position=${holder.bindingAdapterPosition}")
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
