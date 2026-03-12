package com.androidclaw.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.androidclaw.app.R
import com.androidclaw.app.log.LogManager
import com.androidclaw.app.log.TaskLog

/**
 * 日志列表适配器
 */
class LogAdapter : RecyclerView.Adapter<LogAdapter.LogViewHolder>() {

    private val logs = mutableListOf<TaskLog>()

    fun addLog(log: TaskLog) {
        logs.add(log)
        notifyItemInserted(logs.size - 1)
    }

    fun clear() {
        logs.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_log, parent, false)
        return LogViewHolder(view)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(logs[position])
    }

    override fun getItemCount(): Int = logs.size

    class LogViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTime: TextView = itemView.findViewById(R.id.tvLogTime)
        private val tvMessage: TextView = itemView.findViewById(R.id.tvLogMessage)

        fun bind(log: TaskLog) {
            tvTime.text = log.getFormattedTime()
            tvMessage.text = log.message

            val color = when (log.level) {
                LogManager.Level.INFO -> R.color.log_info
                LogManager.Level.WARN -> R.color.log_warn
                LogManager.Level.ERROR -> R.color.log_error
                LogManager.Level.SUCCESS -> R.color.log_success
            }
            tvMessage.setTextColor(itemView.context.getColor(color))
        }
    }
}
