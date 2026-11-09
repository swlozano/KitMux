package com.kitsudo.kitmux.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.kitsudo.kitmux.R
import com.kitsudo.kitmux.data.Folder

/** «Ver carpetas ocultas»: tocar abre sus canciones; «Mostrar» la devuelve al inicio. */
class HiddenFolderAdapter(
    private val onOpen: (Folder) -> Unit,
    private val onUnhide: (Folder) -> Unit,
) : ListAdapter<Folder, HiddenFolderAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_folder_hidden, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.title)
        private val subtitle: TextView = view.findViewById(R.id.subtitle)
        private val unhide: Button = view.findViewById(R.id.unhide)

        init {
            view.setOnClickListener { current()?.let(onOpen) }
            unhide.setOnClickListener { current()?.let(onUnhide) }
        }

        private fun current(): Folder? =
            bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let(::getItem)

        fun bind(folder: Folder) {
            val ctx = itemView.context
            val count = ctx.resources.getQuantityString(R.plurals.song_count, folder.songCount, folder.songCount)
            title.text = folder.name
            subtitle.text = if (folder.parentPath.isEmpty()) count else ctx.getString(R.string.folder_subtitle, count, folder.parentPath)
            unhide.contentDescription = ctx.getString(R.string.unhide_folder, folder.name)
        }
    }

    private object Diff : DiffUtil.ItemCallback<Folder>() {
        override fun areItemsTheSame(oldItem: Folder, newItem: Folder) = oldItem.path == newItem.path
        override fun areContentsTheSame(oldItem: Folder, newItem: Folder) = oldItem == newItem
    }
}
