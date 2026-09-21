package com.landeting.adapter;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.landeting.R;
import com.landeting.model.Song;
import com.landeting.utils.PlayerManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 通用歌曲列表适配器（本地音乐 / 搜索结果共用）。
 *
 * @param <T> 泛型占位，方便以后扩展
 */
public class SongAdapter extends RecyclerView.Adapter<SongAdapter.SongHolder> {

    /** 列表项点击回调 */
    public interface OnSongClickListener {
        void onSongClick(Song song, int position);

        /** 右侧操作按钮（如“下载”）；返回 false 表示隐藏该按钮 */
        boolean onActionClick(Song song, int position);
    }

    private final List<Song> songs = new ArrayList<>();
    private final OnSongClickListener listener;

    public SongAdapter(OnSongClickListener listener) {
        this.listener = listener;
    }

    public void setData(List<Song> data) {
        songs.clear();
        if (data != null) {
            songs.addAll(data);
        }
        notifyDataSetChanged();
    }

    /** 供外部取回完整列表（用于整列表播放） */
    public List<Song> getSongsForPlayback() {
        return new ArrayList<>(songs);
    }

    @NonNull
    @Override
    public SongHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_song, parent, false);
        return new SongHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull SongHolder holder, int position) {
        Song song = songs.get(position);
        holder.tvTitle.setText(song.getTitle());

        // 歌手 - 专辑
        String subtitle = song.getArtist();
        if (song.getAlbum() != null && !song.getAlbum().isEmpty()) {
            subtitle += " - " + song.getAlbum();
        }
        holder.tvSubtitle.setText(subtitle);
        holder.tvDuration.setText(song.durationText());

        // 封面：在线歌曲加载网络图；本地歌曲使用默认图标
        if (song.getCoverUrl() != null && !song.getCoverUrl().isEmpty()) {
            Glide.with(holder.itemView.getContext())
                    .load(song.getCoverUrl())
                    .placeholder(R.drawable.ic_album_placeholder)
                    .error(R.drawable.ic_album_placeholder)
                    .circleCrop()
                    .into(holder.ivCover);
        } else {
            holder.ivCover.setImageResource(R.drawable.ic_album_placeholder);
        }

        // 当前正在播放的歌曲高亮
        Song current = PlayerManager.get().getCurrentSong();
        boolean isCurrent = current != null && current.getId() == song.getId()
                && current.isOnline() == song.isOnline();
        holder.tvTitle.setTextColor(isCurrent
                ? ContextCompat.getColor(holder.itemView.getContext(), R.color.primary)
                : Color.parseColor("#333333"));

        // 右侧操作按钮
        boolean showAction = listener.onActionClick(song, position);
        holder.ivAction.setVisibility(showAction ? View.VISIBLE : View.GONE);

        holder.itemView.setOnClickListener(v -> listener.onSongClick(song, position));
        holder.ivAction.setOnClickListener(v -> listener.onActionClick(song, position));
    }

    @Override
    public int getItemCount() {
        return songs.size();
    }

    static class SongHolder extends RecyclerView.ViewHolder {
        final ImageView ivCover;
        final TextView tvTitle;
        final TextView tvSubtitle;
        final TextView tvDuration;
        final ImageView ivAction;

        SongHolder(@NonNull View itemView) {
            super(itemView);
            ivCover = itemView.findViewById(R.id.iv_cover);
            tvTitle = itemView.findViewById(R.id.tv_title);
            tvSubtitle = itemView.findViewById(R.id.tv_subtitle);
            tvDuration = itemView.findViewById(R.id.tv_duration);
            ivAction = itemView.findViewById(R.id.iv_action);
        }
    }
}
