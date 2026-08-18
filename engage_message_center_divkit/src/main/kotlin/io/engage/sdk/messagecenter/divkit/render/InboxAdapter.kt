package io.engage.sdk.messagecenter.divkit.render

import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.engage.sdk.EngageLogger
import io.engage.sdk.InboxEntry
import io.engage.sdk.InboxEntryId
import io.engage.sdk.messagecenter.divkit.MessageCenterMaterialTheme
import io.engage.sdk.messagecenter.divkit.MessageCenterViewLayout
import kotlinx.coroutines.CoroutineScope

internal class InboxAdapter(
    private val scope: CoroutineScope,
    private val actionRouter: InboxActionRouter,
    private val materialTheme: MessageCenterMaterialTheme,
    private val layout: MessageCenterViewLayout,
    private val onOpenDetail: (InboxUiItem) -> Unit,
) : ListAdapter<InboxUiItem, InboxAdapter.ViewHolder>(DIFF) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            InboxDivKitView(
                parent.context,
                scope,
                actionRouter,
                materialTheme = materialTheme,
                layout = layout,
            ),
        ).also {
            EngageLogger.verbose("MessageCenter.Adapter", "view holder created viewType=$viewType")
        }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        EngageLogger.verbose("MessageCenter.Adapter", "view holder binding position=$position entryId=${item.entry.id}")
        holder.item = item
        holder.view.bind(item)
        holder.view.setOnClickListener {
            val currentPosition = holder.bindingAdapterPosition
            if (currentPosition != RecyclerView.NO_POSITION) onOpenDetail(getItem(currentPosition))
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        EngageLogger.verbose("MessageCenter.Adapter", "view holder recycling position=${holder.bindingAdapterPosition}")
        holder.item = null
        holder.view.recycle()
        super.onViewRecycled(holder)
    }

    internal fun restore(entryId: InboxEntryId) {
        val position = currentList.indexOfFirst { it.entry.id == entryId }
        if (position >= 0) notifyItemChanged(position)
    }

    internal class ViewHolder(val view: InboxDivKitView) : RecyclerView.ViewHolder(view) {
        var item: InboxUiItem? = null

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

internal fun inboxDeleteTarget(item: InboxUiItem?): InboxEntry? = item?.entry
