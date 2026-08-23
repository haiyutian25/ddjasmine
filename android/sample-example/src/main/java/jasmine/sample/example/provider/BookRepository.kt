package jasmine.sample.example.provider

data class Book(val id: Int, val title: String, val author: String)

object BookRepository {
    val books = mutableListOf(
        Book(1, "Compose 插件化开发", "Jasmine"),
        Book(2, "Kotlin 协程入门", "佚名"),
    )
    private var nextId = 3

    fun addBook(title: String, author: String): Book {
        val newBook = Book(nextId++, title, author)
        books.add(newBook)
        return newBook
    }

    fun deleteBook(id: Int): Boolean = books.removeAll { it.id == id }

    fun updateBook(id: Int, newTitle: String, newAuthor: String): Boolean {
        val book = books.find { it.id == id } ?: return false
        val index = books.indexOf(book)
        books[index] = book.copy(title = newTitle, author = newAuthor)
        return true
    }
}
