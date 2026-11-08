package com.kitsudo.kitmux.ui

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.kitsudo.kitmux.R
import com.kitsudo.kitmux.data.Folder

/**
 * Carpetas en lista o cuadrícula.
 * Cuadrícula: botón ▶ visible abajo a la derecha. Lista: el ▶ aparece al deslizar la fila a la izquierda.
 */
class FolderAdapter(
    private val onOpen: (Folder) -> Unit,
    private val onPlay: (Folder) -> Unit,
) : ListAdapter<Folder, FolderAdapter.Holder>(Diff) {

    var layout: FolderLayout = FolderLayout.LIST
        @SuppressLint("NotifyDataSetChanged") // cambian todas las filas de forma
        set(value) {
            if (field == value) return
            field = value
            openPath = null
            notifyDataSetChanged()
        }

    /** Fila de la lista que está deslizada (solo una a la vez). */
    private var openPath: String? = null

    /** Cierra la fila deslizada, si la hay (p. ej. al desplazar la lista). */
    fun closeOpenRow() {
        val path = openPath ?: return
        openPath = null
        currentList.indexOfFirst { it.path == path }.takeIf { it >= 0 }?.let { notifyItemChanged(it, PAYLOAD_CLOSE) }
    }

    private fun onRowOpenChanged(folder: Folder, open: Boolean) {
        if (open) {
            if (openPath != folder.path) closeOpenRow()
            openPath = folder.path
        } else if (openPath == folder.path) {
            openPath = null
        }
    }

    private var playingFolder: String? = null
    private var isPlaying = false

    /** Carpeta de la canción actual y si está sonando (el botón pasa a ⏸). */
    fun setPlayback(folder: String?, playing: Boolean) {
        if (folder == playingFolder && playing == isPlaying) return
        val old = playingFolder
        playingFolder = folder
        isPlaying = playing
        currentList.forEachIndexed { i, f -> if (f.path == old || f.path == folder) notifyItemChanged(i) }
    }

    override fun getItemViewType(position: Int) = layout.ordinal

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val res = if (viewType == FolderLayout.GRID.ordinal) R.layout.item_folder_grid else R.layout.item_folder_list
        return Holder(LayoutInflater.from(parent.context).inflate(res, parent, false))
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_CLOSE }) {
            holder.swipe?.setOpen(open = false, animate = true)
        } else {
            holder.bind(getItem(position))
        }
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val iconContainer: View? = view.findViewById(R.id.iconContainer)
        private val icon: ImageView? = view.findViewById(R.id.icon)
        private val title: TextView = view.findViewById(R.id.title)
        private val subtitle: TextView = view.findViewById(R.id.subtitle)
        private val play: ImageButton = view.findViewById(R.id.play)
        val swipe: SwipeRevealLayout? = view.findViewById(R.id.swipe)
        private val front: View = view.findViewById(R.id.front) ?: view
        private var playActionId = View.NO_ID

        init {
            front.setOnClickListener {
                // Con la fila deslizada, tocarla solo la cierra.
                if (swipe?.isOpen == true) {
                    swipe.setOpen(open = false, animate = true)
                    currentFolder()?.let { onRowOpenChanged(it, false) }
                } else {
                    currentFolder()?.let(onOpen)
                }
            }
            play.setOnClickListener {
                val folder = currentFolder() ?: return@setOnClickListener
                onPlay(folder)
                if (swipe != null) {
                    swipe.setOpen(open = false, animate = true)
                    onRowOpenChanged(folder, false)
                }
            }
            swipe?.onOpenChanged = { open -> currentFolder()?.let { onRowOpenChanged(it, open) } }
        }

        private fun currentFolder(): Folder? =
            bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let(::getItem)

        fun bind(folder: Folder) {
            val ctx = itemView.context
            val count = ctx.resources.getQuantityString(R.plurals.song_count, folder.songCount, folder.songCount)
            title.text = folder.name
            subtitle.text = if (layout == FolderLayout.LIST && folder.parentPath.isNotEmpty()) {
                ctx.getString(R.string.folder_subtitle, count, folder.parentPath)
            } else {
                count
            }

            val current = folder.path == playingFolder
            val playingHere = current && isPlaying
            play.setImageResource(if (playingHere) R.drawable.ic_pause else R.drawable.ic_play)
            // Naranja intenso de marca: carpeta seleccionada (sonando o en pausa) y siempre en la lista,
            // donde el botón solo aparece al deslizar. En la cuadrícula, el resto va en gris.
            val brand = current || swipe != null
            val ctxColor = { res: Int -> ContextCompat.getColor(ctx, res) }
            play.backgroundTintList = ColorStateList.valueOf(
                if (brand) ctxColor(R.color.color_brand) else color(com.google.android.material.R.attr.colorSecondaryContainer),
            )
            ImageViewCompat.setImageTintList(
                play,
                ColorStateList.valueOf(
                    if (brand) ctxColor(R.color.color_on_brand) else color(com.google.android.material.R.attr.colorSecondary),
                ),
            )
            play.elevation = if (brand) ctx.resources.displayMetrics.density * 2 else 0f
            play.contentDescription = ctx.getString(if (playingHere) R.string.folder_pause else R.string.folder_play, folder.name)

            swipe?.setOpen(open = folder.path == openPath, animate = false)
            if (swipe != null) {
                // El gesto no es accesible: TalkBack ofrece la misma acción en el menú de acciones.
                if (playActionId != View.NO_ID) ViewCompat.removeAccessibilityAction(front, playActionId)
                playActionId = ViewCompat.addAccessibilityAction(front, play.contentDescription) { _, _ ->
                    onPlay(folder)
                    true
                }
            }

            title.setTextColor(color(if (current) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurface))
            title.setTypeface(null, if (current) Typeface.BOLD else Typeface.NORMAL)
            iconContainer?.backgroundTintList = ColorStateList.valueOf(
                color(if (current) com.google.android.material.R.attr.colorPrimaryContainer else com.google.android.material.R.attr.colorSurfaceContainerHighest),
            )
            icon?.setColorFilter(
                color(if (current) com.google.android.material.R.attr.colorOnPrimaryContainer else com.google.android.material.R.attr.colorOnSurfaceVariant),
            )
        }

        private fun color(attr: Int) = MaterialColors.getColor(itemView, attr)
    }

    private companion object {
        const val PAYLOAD_CLOSE = "close"
    }

    private object Diff : DiffUtil.ItemCallback<Folder>() {
        override fun areItemsTheSame(oldItem: Folder, newItem: Folder) = oldItem.path == newItem.path
        override fun areContentsTheSame(oldItem: Folder, newItem: Folder) = oldItem == newItem
    }
}
