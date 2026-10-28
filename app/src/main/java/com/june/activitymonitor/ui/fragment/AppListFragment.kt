package com.june.activitymonitor.ui.fragment

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.june.activitymonitor.R
import com.june.activitymonitor.databinding.FragmentAppListBinding
import com.june.activitymonitor.data.ProcessRepository
import com.june.activitymonitor.model.AppCategory
import com.june.activitymonitor.model.AppProcessInfo
import com.june.activitymonitor.model.MonitorUiState
import com.june.activitymonitor.ui.MonitorViewModel
import com.june.activitymonitor.ui.MainActivity
import com.june.activitymonitor.ui.adapter.AppListAdapter
import kotlinx.coroutines.launch

class AppListFragment : Fragment() {

    private var _binding: FragmentAppListBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MonitorViewModel by activityViewModels()

    private val category: AppCategory
        get() = AppCategory.valueOf(requireArguments().getString(ARG_CATEGORY)!!)

    private lateinit var adapter: AppListAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentAppListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = AppListAdapter(
            onEndClicked = { app -> confirmEnd(app) },
            onRowLongClicked = { app -> showAppActions(app) },
        )
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter
        binding.swipeRefresh.setOnRefreshListener { viewModel.refresh() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state -> render(state) }
            }
        }
    }

    private fun render(state: MonitorUiState) {
        val apps = if (category == AppCategory.SYSTEM) state.systemApps else state.userApps
        adapter.submitList(apps)

        val empty = apps.isEmpty()
        binding.emptyView.isVisible = empty
        binding.progress.isVisible = empty && state.loading
        binding.swipeRefresh.isRefreshing = state.loading && !empty
        if (empty) {
            val degraded = !state.privileged && !state.usageAccessGranted
            val needsAccess = !state.loading && degraded
            binding.emptyText.text = getString(
                when {
                    state.loading -> R.string.loading
                    needsAccess -> R.string.empty_need_helper
                    else -> R.string.empty_list
                }
            )
            binding.emptyView.isClickable = needsAccess
            binding.emptyView.setOnClickListener(
                if (needsAccess) View.OnClickListener { openSetup() } else null
            )
        }
    }

    private fun openSetup() {
        (activity as? MainActivity)?.showHelperDialog()
    }

    /** Long press menu: freeze (block auto-start), disable (turn off) or restore the app. */
    private fun showAppActions(app: AppProcessInfo) {
        if (app.packageName == requireContext().packageName) {
            toast(getString(R.string.kill_self_blocked))
            return
        }
        if (!viewModel.state.value.privileged) {
            toast(getString(R.string.control_need_helper))
            return
        }

        val choices = mutableListOf<ControlChoice>()
        if (app.isFrozen || app.isDisabled) {
            choices += ControlChoice(
                ProcessRepository.ControlAction.RESTORE,
                getString(R.string.control_restore),
                getString(R.string.control_restore_hint),
            )
        }
        // Freezing / disabling is only offered for user apps: doing it to a system app or service can
        // break system features, so system entries can only be ended (with the existing warning).
        if (!app.isSystem) {
            if (!app.isFrozen && !app.isDisabled) {
                choices += ControlChoice(
                    ProcessRepository.ControlAction.FREEZE,
                    getString(R.string.control_freeze),
                    getString(R.string.control_freeze_hint),
                )
            }
            if (!app.isDisabled) {
                choices += ControlChoice(
                    ProcessRepository.ControlAction.DISABLE,
                    getString(R.string.control_disable),
                    getString(R.string.control_disable_hint),
                )
            }
        }

        if (choices.isEmpty()) {
            toast(getString(R.string.control_system_not_supported))
            return
        }

        val choiceAdapter = object : ArrayAdapter<ControlChoice>(
            requireContext(),
            R.layout.item_control_action,
            choices,
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_control_action, parent, false)
                val choice = getItem(position) ?: return view
                view.findViewById<TextView>(R.id.control_title).text = choice.title
                view.findViewById<TextView>(R.id.control_description).text = choice.description
                return view
            }
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(app.appName)
            .setAdapter(choiceAdapter) { _, which ->
                runControl(app, choices[which].action)
            }
            .setNegativeButton(R.string.kill_cancel, null)
            .show()
    }

    private data class ControlChoice(
        val action: ProcessRepository.ControlAction,
        val title: String,
        val description: String,
    )

    private fun runControl(app: AppProcessInfo, action: ProcessRepository.ControlAction) {
        viewModel.controlApp(app, action) { outcome ->
            if (!isAdded) return@controlApp
            val message = if (outcome == ProcessRepository.ControlOutcome.DONE) {
                getString(
                    when (action) {
                        ProcessRepository.ControlAction.FREEZE -> R.string.control_freeze_done
                        ProcessRepository.ControlAction.DISABLE -> R.string.control_disable_done
                        ProcessRepository.ControlAction.RESTORE -> R.string.control_restore_done
                    },
                    app.appName,
                )
            } else {
                getString(R.string.control_failed, app.appName)
            }
            toast(message)
        }
    }

    private fun confirmEnd(app: AppProcessInfo) {
        if (app.packageName == requireContext().packageName) {
            toast(getString(R.string.kill_self_blocked))
            return
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setNegativeButton(R.string.kill_cancel, null)
            .setPositiveButton(R.string.kill_confirm) { _, _ ->
                viewModel.killApp(app) { outcome ->
                    if (!isAdded) return@killApp
                    val message = getString(
                        when (outcome) {
                            ProcessRepository.KillOutcome.STOPPED -> R.string.kill_done
                            ProcessRepository.KillOutcome.BEST_EFFORT -> R.string.kill_best_effort
                            ProcessRepository.KillOutcome.FAILED -> R.string.kill_failed
                        },
                        app.appName,
                    )
                    toast(message)
                }
            }

        // Without shell privilege the system may ignore a request from a third-party app, so offer
        // the guaranteed route: the system "App info" screen where the user can force stop it.
        if (!viewModel.state.value.privileged) {
            dialog.setNeutralButton(R.string.kill_open_settings) { _, _ -> openAppDetails(app) }
        }

        if (app.isSystem) {
            dialog.setTitle(R.string.kill_system_title)
                .setMessage(getString(R.string.kill_system_message, app.appName))
        } else {
            dialog.setTitle(R.string.kill_user_title)
                .setMessage(getString(R.string.kill_user_message, app.appName))
        }

        dialog.show()
    }

    private fun openAppDetails(app: AppProcessInfo) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", app.packageName, null),
        )
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            toast(getString(R.string.kill_open_settings_failed))
        }
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.list.adapter = null
        _binding = null
    }

    companion object {
        private const val ARG_CATEGORY = "arg_category"

        fun newInstance(category: AppCategory): AppListFragment = AppListFragment().apply {
            arguments = Bundle().apply { putString(ARG_CATEGORY, category.name) }
        }
    }
}
