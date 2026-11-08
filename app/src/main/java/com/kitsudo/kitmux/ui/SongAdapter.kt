package com.kitsudo.kitmux.ui

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.kitsudo.kitmux.R
import com.kitsudo.kitmux.data.LibraryUtils
import com.kitsudo.kitmux.data.Song
import com.kitsudo.kitmux.databinding.ItemSongBinding

class SongAdapter(
    private val onSongClick: (position: Int) -> Unit,
) : ListAdapter<Song, SongAdapter.ViewHolder>(Diff) {

    private var currentSongId: String? = null

    fun setCurrentSong(id: String?) {
        if (id == currentSongId) return
        val old = currentSongId
        currentSongId = id
        currentList.forEachIndexed { index, song ->
            if (song.id == old || song.id == id) notifyItemChanged(index)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemSongBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position), getItem(position).id == currentSongId)

    inner class ViewHolder(private val binding: ItemSongBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onSongClick(pos)
            }
        }

        fun bind(song: Song, isCurrent: Boolean) {
            val ctx = binding.root.context
            binding.title.text = song.title
            val artist = song.artist ?: ctx.getString(R.string.unknown_artist)
            binding.subtitle.text = ctx.getString(
                R.string.song_subtitle, artist, LibraryUtils.formatDuration(song.durationMs),
            )
            // Canción actual: contenedor naranja suave y título en el color primario.
            val view = binding.root
            binding.title.setTextColor(
                MaterialColors.getColor(view, if (isCurrent) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurface),
            )
            binding.title.setTypeface(null, if (isCurrent) Typeface.BOLD else Typeface.NORMAL)
            binding.iconContainer.backgroundTintList = ColorStateList.valueOf(
                MaterialColors.getColor(
                    view,
                    if (isCurrent) com.google.android.material.R.attr.colorPrimaryContainer else com.google.android.material.R.attr.colorSurfaceContainerHighest,
                ),
            )
            binding.icon.setColorFilter(
                MaterialColors.getColor(
                    view,
                    if (isCurrent) com.google.android.material.R.attr.colorOnPrimaryContainer else com.google.android.material.R.attr.colorOnSurfaceVariant,
                ),
            )
        }
    }

    private object Diff : DiffUtil.ItemCallback<Song>() {
        override fun areItemsTheSame(oldItem: Song, newItem: Song) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Song, newItem: Song) = oldItem == newItem
    }
}
