package com.june.activitymonitor.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.june.activitymonitor.R
import com.june.activitymonitor.databinding.ItemAppBinding
import com.june.activitymonitor.model.AppProcessInfo
import com.june.activitymonitor.util.Formatters

class AppListAdapter(
    private val onEndClicked: (AppProcessInfo) -> Unit,
    private val onRowLongClicked: (AppProcessInfo) -> Unit,
) : ListAdapter<AppProcessInfo, AppListAdapter.AppViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder =
        AppViewHolder(
            ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            onEndClicked,
            onRowLongClicked,
        )

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class AppViewHolder(
        private val binding: ItemAppBinding,
        private val onEndClicked: (AppProcessInfo) -> Unit,
        private val onRowLongClicked: (AppProcessInfo) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: AppProcessInfo) = with(binding) {
            val context = root.context
            appIcon.setImageDrawable(
                item.icon ?: ContextCompat.getDrawable(context, R.drawable.ic_launcher)
            )
            appName.text = item.appName
            packageName.text = item.packageName

            val unknown = context.getString(R.string.value_unknown)
            cpuText.text = context.getString(
                R.string.summary_cpu,
                if (item.hasCpu) Formatters.percent(item.cpuPercent) else unknown,
            )
            memoryText.text = context.getString(
                R.string.summary_memory,
                if (item.hasMemory) Formatters.bytes(item.memoryBytes) else unknown,
            )

            if (item.serviceCount > 0) {
                servicesText.visibility = View.VISIBLE
                servicesText.text = context.getString(R.string.summary_services, item.serviceCount)
            } else {
                servicesText.visibility = View.GONE
            }

            statusText.text = context.getString(
                when {
                    item.isDisabled -> R.string.status_disabled
                    item.isFrozen -> R.string.status_frozen
                    item.isRunning -> R.string.status_running
                    else -> R.string.status_stopped
                }
            )
            statusText.setTextColor(
                ContextCompat.getColor(
                    context,
                    when {
                        item.isDisabled -> R.color.status_stopped
                        item.isFrozen -> R.color.level_mid
                        item.isRunning -> R.color.status_running
                        else -> R.color.status_stopped
                    },
                )
            )

            val isSelf = item.packageName == context.packageName
            endButton.setOnClickListener { onEndClicked(item) }
            endButton.isVisible = !isSelf && !item.isControlled
            manageButton.isVisible = !item.isSystem && !isSelf
            manageButton.setOnClickListener { onRowLongClicked(item) }
            if (isSelf) {
                root.setOnLongClickListener(null)
                root.isLongClickable = false
            } else {
                root.setOnLongClickListener {
                    onRowLongClicked(item)
                    true
                }
                root.isLongClickable = true
            }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<AppProcessInfo>() {
            override fun areItemsTheSame(oldItem: AppProcessInfo, newItem: AppProcessInfo): Boolean =
                oldItem.packageName == newItem.packageName

            override fun areContentsTheSame(oldItem: AppProcessInfo, newItem: AppProcessInfo): Boolean =
                oldItem == newItem
        }
    }
}
