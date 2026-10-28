package com.june.activitymonitor.ui.adapter

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.june.activitymonitor.model.AppCategory
import com.june.activitymonitor.ui.fragment.AppListFragment

class AppPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = 2

    override fun createFragment(position: Int): Fragment =
        AppListFragment.newInstance(
            if (position == 0) AppCategory.SYSTEM else AppCategory.USER
        )
}
