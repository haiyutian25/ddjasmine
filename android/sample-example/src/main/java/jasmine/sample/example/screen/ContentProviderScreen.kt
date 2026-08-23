package jasmine.sample.example.screen

import android.content.ContentUris
import android.content.ContentValues
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.lhzkml.jasmine.core.plugin.proxy.deletePlugin
import com.lhzkml.jasmine.core.plugin.proxy.insertPlugin
import com.lhzkml.jasmine.core.plugin.proxy.queryPlugin
import com.lhzkml.jasmine.core.plugin.proxy.registerPluginObserver
import com.lhzkml.jasmine.core.plugin.proxy.unregisterPluginObserver
import com.lhzkml.jasmine.core.plugin.proxy.updatePlugin
import jasmine.sample.example.provider.Book
import jasmine.sample.example.provider.BookProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val BOOKS_URI: Uri = "content://${BookProvider.AUTHORITY}/books".toUri()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContentProviderScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var books by remember { mutableStateOf<List<Book>>(emptyList()) }
    var showBottomSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    var editingBook by remember { mutableStateOf<Book?>(null) }

    val queryBooks = {
        coroutineScope.launch {
            val bookList = withContext(Dispatchers.IO) {
                val cursor = context.queryPlugin(BOOKS_URI, null, null, null, null)
                mutableListOf<Book>().apply {
                    cursor?.use {
                        while (it.moveToNext()) {
                            add(Book(it.getInt(it.getColumnIndexOrThrow("_id")), it.getString(it.getColumnIndexOrThrow("title")), it.getString(it.getColumnIndexOrThrow("author"))))
                        }
                    }
                }
            }
            books = bookList
        }
    }

    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { queryBooks() }
        }
        context.registerPluginObserver(BOOKS_URI, true, observer)
        queryBooks()
        onDispose { context.unregisterPluginObserver(observer) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("内容提供者示例", fontWeight = FontWeight.Bold) }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text("添加新书") },
                icon = { Icon(Icons.Filled.Add, contentDescription = "添加新书") },
                onClick = { editingBook = null; showBottomSheet = true },
            )
        },
    ) { paddingValues ->
        AnimatedContent(
            targetState = books.isEmpty(),
            modifier = Modifier.padding(paddingValues).fillMaxSize(),
            label = "list-animation",
        ) { isEmpty ->
            if (isEmpty) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "书库是空的，\n点击右下角按钮添加一本吧！",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(books, key = { it.id }) { book ->
                        BookListItem(
                            book = book,
                            onClick = { editingBook = book; showBottomSheet = true },
                            onDelete = {
                                coroutineScope.launch(Dispatchers.IO) {
                                    val deleteUri = ContentUris.withAppendedId(BOOKS_URI, book.id.toLong())
                                    context.deletePlugin(deleteUri, null, null)
                                }
                            },
                        )
                    }
                }
            }
        }
        if (showBottomSheet) {
            ModalBottomSheet(onDismissRequest = { showBottomSheet = false }, sheetState = sheetState) {
                AddEditBookSheetContent(
                    editingBook = editingBook,
                    onSave = { title, author ->
                        coroutineScope.launch(Dispatchers.IO) {
                            val values = ContentValues().apply { put("title", title); put("author", author) }
                            if (editingBook != null) {
                                val updateUri = ContentUris.withAppendedId(BOOKS_URI, editingBook!!.id.toLong())
                                context.updatePlugin(updateUri, values, null, null)
                            } else {
                                context.insertPlugin(BOOKS_URI, values)
                            }
                            withContext(Dispatchers.Main) { showBottomSheet = false }
                        }
                    },
                    onCancel = { showBottomSheet = false },
                )
            }
        }
    }
}

@Composable
private fun AddEditBookSheetContent(editingBook: Book?, onSave: (title: String, author: String) -> Unit, onCancel: () -> Unit) {
    var title by remember { mutableStateOf(editingBook?.title ?: "") }
    var author by remember { mutableStateOf(editingBook?.author ?: "") }
    val isEditing = editingBook != null
    LaunchedEffect(editingBook) { title = editingBook?.title ?: ""; author = editingBook?.author ?: "" }
    Column(modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = if (isEditing) "编辑书籍" else "添加新书",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("书名") }, leadingIcon = { Icon(Icons.Default.AccountBox, contentDescription = null) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = author, onValueChange = { author = it }, label = { Text("作者") }, leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End)) {
            TextButton(onClick = onCancel) { Text("取消") }
            Button(onClick = { onSave(title, author) }, enabled = title.isNotBlank()) { Text(if (isEditing) "更新" else "添加") }
        }
    }
}

@Composable
private fun BookListItem(book: Book, onClick: () -> Unit, onDelete: () -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        ListItem(
            headlineContent = { Text(book.title, fontWeight = FontWeight.SemiBold) },
            supportingContent = { Text(book.author) },
            leadingContent = { Icon(Icons.Default.AccountBox, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            trailingContent = {
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error) }
            },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        )
    }
}
