package com.ubadahj.qidianundergroud.ui.main

import android.os.Bundle
import android.text.Editable
import android.view.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.github.ajalt.timberkt.e
import com.ubadahj.qidianundergroud.R
import com.ubadahj.qidianundergroud.databinding.BookListFragmentBinding
import com.ubadahj.qidianundergroud.models.Book
import com.ubadahj.qidianundergroud.models.Resource
import com.ubadahj.qidianundergroud.services.IndexService
import com.ubadahj.qidianundergroud.ui.adapters.BookAdapter
import com.ubadahj.qidianundergroud.ui.dialog.InputBottomSheet
import com.ubadahj.qidianundergroud.ui.dialog.InputBottomSheetConfig
import com.ubadahj.qidianundergroud.utils.ui.onItemSelectedListener
import com.ubadahj.qidianundergroud.utils.ui.snackBar
import com.ubadahj.qidianundergroud.utils.ui.visible
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@AndroidEntryPoint
class BrowseFragment : Fragment() {

    private val viewModel: MainViewModel by activityViewModels()

    private var binding: BookListFragmentBinding? = null
    private val adapter: BookAdapter = BookAdapter(listOf()) {
        viewModel.setSelectedBook(it)
        findNavController().navigate(
            BrowseFragmentDirections.actionBrowseFragmentToBookFragment()
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = BookListFragmentBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setHasOptionsMenu(true)

        binding?.apply {
            (requireActivity() as AppCompatActivity).setSupportActionBar(toolbar.appbar)
            toolbar.appbar.title = resources.getText(R.string.browse)

            bookListingView.layoutManager = LinearLayoutManager(requireContext())
            bookListingView.adapter = adapter

            searchBar.searchEditText.addTextChangedListener { text: Editable? ->
                adapter.filter.filter(text)
            }

            sortBySpinner.onItemSelectedListener { position ->
                val sortBy = when (position) {
                    1 -> Book::author
                    2 -> Book::rating
                    3 -> Book::lastRead
                    4 -> Book::completed
                    else -> Book::name
                }

                adapter.sortBy(sortBy, descendingSwitch.isChecked)
            }

            descendingSwitch.isUseMaterialThemeColors = true
            descendingSwitch.setOnClickListener { adapter.sortBy(reverse = true) }
        }

        lifecycleScope.launch {
            viewModel
                .getBooks()
                .flowWithLifecycle(lifecycle)
                .collect { getBooks(it) }
        }
    }

    private fun getBooks(
        resource: Resource<List<Book>>,
        isRefresh: Boolean = false
    ) {
        when (resource) {
            is Resource.Success -> {
                binding?.apply {
                    progressBar.visible = false
                    if (isRefresh) {
                        val count = resource.data.size - adapter.currentList.size
                        if (count == 0)
                            root.snackBar("No new books found!")
                        else
                            root.snackBar("$count new books added")
                    }
                }

                adapter.submitList(resource.data)
                binding?.searchBar?.searchEditText?.text?.apply {
                    if (isNotEmpty()) adapter.filter.filter(this)
                }
            }
            Resource.Loading -> binding?.progressBar?.visible = true
            is Resource.Error -> {
                binding?.apply {
                    progressBar.visible = false
                    root.snackBar(R.string.error_refreshing)
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.search -> {
                binding?.apply {
                    val searchBarVisible = bookListingViewContainer.y != searchBar.root.y
                    bookListingViewContainer.animate()
                        .alpha(1f)
                        .translationY(if (!searchBarVisible) sortBySpinner.y + 32f else 0f)
                        .start()
                }
                true
            }
            R.id.menu_refresh_books -> {
                lifecycleScope.launch {
                    viewModel
                        .getBooks(refresh = true)
                        .flowWithLifecycle(lifecycle)
                        .collect { getBooks(it, true) }
                }
                true
            }
            R.id.menu_generate_index -> {
                val work = OneTimeWorkRequestBuilder<IndexService>().build()
                WorkManager.getInstance(requireContext()).enqueueUniqueWork(
                    "index-service", ExistingWorkPolicy.KEEP, work
                )
                true
            }
            R.id.add -> {
                InputBottomSheet(
                    InputBottomSheetConfig(
                        "Add book from link",
                        "Add a new book from directly entering a link from WebNovel website",
                        R.drawable.external_link,
                        { openBookFromLink(it) },
                        "Open"
                    )
                ).show(requireActivity().supportFragmentManager, null)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun openBookFromLink(link: String) {
        lifecycleScope.launch {
            viewModel.getWebNovelBook(link).flowWithLifecycle(lifecycle).collect { res ->
                when (res) {
                    Resource.Loading -> binding?.apply {
                        root.snackBar("Loading...")
                    }
                    is Resource.Success -> {
                        viewModel.setSelectedBook(res.data)
                        findNavController().navigate(
                            BrowseFragmentDirections.actionBrowseFragmentToBookFragment()
                        )
                        cancel()
                    }
                    is Resource.Error -> binding?.apply {
                        e(res.message)
                        root.snackBar("Failed to open link: ${res.message}")
                    }
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.browse_menu, menu)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
