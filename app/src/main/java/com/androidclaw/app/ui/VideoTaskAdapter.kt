package com.androidclaw.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.androidclaw.app.R
import com.androidclaw.app.task.VideoTask

/**
 * 视频任务队列适配器
 */
class VideoTaskAdapter(
    private val onItemClick: ((VideoTask) -> Unit)? = null
) : ListAdapter<VideoTask, VideoTaskAdapter.VideoTaskViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoTaskViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_video_task, parent, false)
        return VideoTaskViewHolder(view)
    }

    override fun onBindViewHolder(holder: VideoTaskViewHolder, position: Int) {
        holder.bind(getItem(position), onItemClick)
    }

    class VideoTaskViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tvVideoName)
        private val tvPath: TextView = itemView.findViewById(R.id.tvVideoPath)
        private val tvStatus: TextView = itemView.findViewById(R.id.tvVideoStatus)

        fun bind(task: VideoTask, onItemClick: ((VideoTask) -> Unit)?) {
            tvName.text = task.name
            
            if (task.status == VideoTask.Status.COMPLETED && task.resultPath != null) {
                tvPath.text = "点击查看结果: ${task.resultPath}"
                tvPath.setTextColor(itemView.context.getColor(R.color.accent))
            } else {
                tvPath.text = task.uri.toString()
                tvPath.setTextColor(itemView.context.getColor(R.color.text_secondary))
            }

            itemView.setOnClickListener {
                onItemClick?.invoke(task)
            }

            when (task.status) {
                VideoTask.Status.WAITING -> {
                    tvStatus.text = "等待中"
                    tvStatus.setTextColor(itemView.context.getColor(R.color.text_secondary))
                    tvStatus.setBackgroundColor(0x33666666)
                }
                VideoTask.Status.PROCESSING -> {
                    tvStatus.text = "处理中"
                    tvStatus.setTextColor(itemView.context.getColor(R.color.running))
                    tvStatus.setBackgroundColor(0x332196F3)
                }
                VideoTask.Status.COMPLETED -> {
                    tvStatus.text = "已完成"
                    tvStatus.setTextColor(itemView.context.getColor(R.color.success))
                    tvStatus.setBackgroundColor(0x334CAF50)
                }
                VideoTask.Status.FAILED -> {
                    tvStatus.text = "失败"
                    tvStatus.setTextColor(itemView.context.getColor(R.color.error))
                    tvStatus.setBackgroundColor(0x33F44336)
                }
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<VideoTask>() {
        override fun areItemsTheSame(oldItem: VideoTask, newItem: VideoTask): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: VideoTask, newItem: VideoTask): Boolean {
            return oldItem == newItem
        }
    }
}
