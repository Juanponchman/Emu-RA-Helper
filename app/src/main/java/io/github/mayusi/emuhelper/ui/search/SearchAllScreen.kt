package io.github.mayusi.emuhelper.ui.search

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.emuhelper.data.source.RemoteSource
import io.github.mayusi.emuhelper.ui.common.Dimens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject

/**
 * One row from the Internet Archive advanced-search response.
 * `downloads` is the all-time download count IA reports for the item (may be null/absent).
 */
data class IaSearchResult(
    val identifier: String,
    val title: String,
    val mediatype: String,
    val downloads: Int?
)

/** Distinct UI phases for the search screen. */
sealed interface SearchUiState {
    data object Idle : SearchUiState
    data object Loading : SearchUiState
    data class Results(val items: List<IaSearchResult>) : SearchUiState
    data object Empty : SearchUiState
    data class Error(val message: String) : SearchUiState
}

@HiltViewModel
class SearchViewModel @Inject constructor(
    okHttpClient: OkHttpClient,
    // RemoteSource is injected so it COULD be reused for the per-item load, but the screen routes
    // the actual "identifier -> picker" load through the shared BrowseViewModel.loadIdentifierIntoPicker
    // (the same path the paste-a-link feature uses). It's kept here to keep the search self-contained.
    @Suppress("unused") private val source: RemoteSource
) : ViewModel() {

    /**
     * Cookie-less client: the public advancedsearch API needs no auth and must NOT carry the
     * archive.org session cookies (same pattern the updater uses for GitHub). Shares the parent
     * client's connection pool / dispatcher via newBuilder().
     */
    private val searchClient: OkHttpClient =
        okHttpClient.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val _state = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    /**
     * Run a search against the Internet Archive's public advanced-search API. Debouncing /
     * submit-triggering is the caller's concern; this just performs one search for [query].
     */
    fun search(query: String) {
        val q = query.trim()
        searchJob?.cancel()
        if (q.isEmpty()) {
            _state.value = SearchUiState.Idle
            return
        }
        _state.value = SearchUiState.Loading
        searchJob = viewModelScope.launch {
            try {
                val body = withContext(Dispatchers.IO) {
                    val url = buildSearchUrl(q)
                    val req = Request.Builder()
                        .url(url)
                        .header("Accept", "application/json")
                        .header("User-Agent", "EmuHelper")
                        .build()
                    searchClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                        resp.body?.string() ?: throw IOException("Empty response")
                    }
                }
                val results = parseSearchResults(body, json)
                _state.value = if (results.isEmpty()) SearchUiState.Empty
                               else SearchUiState.Results(results)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("EmuHelper", "IA search failed", e)
                _state.value = SearchUiState.Error(
                    if (e is IOException) "Couldn't reach the Internet Archive. Check your connection."
                    else "Search failed. Try a different query."
                )
            }
        }
    }

    fun clear() {
        searchJob?.cancel()
        _state.value = SearchUiState.Idle
    }

    companion object {
        private const val ROWS = 50

        /**
         * Build the public advanced-search URL. Pure + testable. Requests only the fields the UI
         * shows. Stays content-neutral: no mediatype filter, so it searches ALL public IA items.
         */
        fun buildSearchUrl(query: String): String {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            return "https://archive.org/advancedsearch.php?q=$encoded" +
                "&fl[]=identifier&fl[]=title&fl[]=mediatype&fl[]=downloads" +
                "&rows=$ROWS&page=1&output=json"
        }

        /**
         * Parse the advancedsearch JSON ({ response: { numFound, docs: [...] } }) into a list of
         * [IaSearchResult]. Pure function (no I/O) so it can be unit-tested. Rows missing an
         * identifier are skipped (an item with no identifier can't be opened). `title` falls back
         * to the identifier when absent; `mediatype`/`downloads` are best-effort.
         */
        fun parseSearchResults(body: String, json: Json): List<IaSearchResult> {
            val root = json.parseToJsonElement(body).jsonObject
            val docs = root["response"]?.jsonObject?.get("docs")?.jsonArray ?: return emptyList()
            // IA occasionally returns a field as an array (e.g. title), so read every field through
            // a guarded helper that returns null for non-primitive values instead of throwing.
            fun str(obj: kotlinx.serialization.json.JsonObject, key: String): String? =
                (obj[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.trim()
            return docs.mapNotNull { el ->
                val obj = el.jsonObject
                val id = str(obj, "identifier")
                if (id.isNullOrBlank()) return@mapNotNull null
                val title = str(obj, "title")?.takeIf { it.isNotEmpty() } ?: id
                val mediatype = str(obj, "mediatype") ?: ""
                val downloads = (obj["downloads"] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull
                IaSearchResult(identifier = id, title = title, mediatype = mediatype, downloads = downloads)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchAllScreen(
    /** Called with a tapped result's identifier once the shared loader has populated the picker. */
    onOpenIdentifier: (identifier: String, title: String, onError: (String) -> Unit) -> Unit,
    onBack: () -> Unit,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    var query by remember { mutableStateOf("") }
    var loadError by remember { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }

    // Debounce: search ~350ms after the user stops typing.
    LaunchedEffect(query) {
        delay(350)
        viewModel.search(query)
    }

    LaunchedEffect(loadError) {
        if (loadError.isNotBlank()) {
            snackbar.showSnackbar(loadError)
            loadError = ""
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Search the Internet Archive", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.ScreenHorizontal, vertical = 8.dp),
                placeholder = { Text("Search archive.org…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { viewModel.search(query) })
            )

            when (val s = state) {
                is SearchUiState.Idle -> CenterMessage(
                    icon = { Icon(Icons.Default.Search, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)) },
                    title = "Search the Internet Archive",
                    body = "Find any public item by name, then pick its files to download."
                )
                is SearchUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
                is SearchUiState.Empty -> CenterMessage(
                    icon = { Icon(Icons.Default.SearchOff, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)) },
                    title = "No results",
                    body = "Nothing matched that search. Try different words."
                )
                is SearchUiState.Error -> CenterMessage(
                    icon = { Icon(Icons.Default.SearchOff, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error) },
                    title = "Search error",
                    body = s.message
                )
                is SearchUiState.Results -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(horizontal = Dimens.ScreenHorizontal, vertical = 4.dp)
                ) {
                    items(s.items, key = { it.identifier }) { item ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onOpenIdentifier(item.identifier, item.title) { msg -> loadError = msg }
                                },
                            color = MaterialTheme.colorScheme.surface,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                                Text(
                                    item.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    item.identifier,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (item.mediatype.isNotBlank()) {
                                        Text(
                                            item.mediatype,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    item.downloads?.let { d ->
                                        Text(
                                            "$d downloads",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CenterMessage(icon: @Composable () -> Unit, title: String, body: String) {
    Box(modifier = Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            icon()
            Spacer(Modifier.height(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}
