package com.ubadahj.qidianundergroud.repositories

import android.content.Context
import com.github.ajalt.timberkt.e
import com.squareup.sqldelight.runtime.coroutines.asFlow
import com.squareup.sqldelight.runtime.coroutines.mapToList
import com.squareup.sqldelight.runtime.coroutines.mapToOne
import com.ubadahj.qidianundergroud.Database
import com.ubadahj.qidianundergroud.api.UndergroundApi
import com.ubadahj.qidianundergroud.api.WebNovelApi
import com.ubadahj.qidianundergroud.api.models.underground.UndergroundGroup
import com.ubadahj.qidianundergroud.api.models.webnovel.WNChapterRemote
import com.ubadahj.qidianundergroud.models.BaseGroup
import com.ubadahj.qidianundergroud.models.Book
import com.ubadahj.qidianundergroud.models.Group
import com.ubadahj.qidianundergroud.preferences.LibraryPreferences
import com.ubadahj.qidianundergroud.utils.models.source
import com.ubadahj.qidianundergroud.utils.models.total
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GroupRepository @Inject constructor(
    @ApplicationContext val context: Context,
    private val database: Database,
    private val undergroundApi: UndergroundApi,
    private val webNovelApi: WebNovelApi,
    private val metaRepo: MetadataRepository,
    private val libraryPreferences: LibraryPreferences
) {

    fun getBook(group: Group) = database.bookQueries
        .getById(group.bookId)
        .asFlow()
        .mapToOne()

    fun getChaptersByBook(group: Group) = database.groupQueries
        .getByBookId(group.bookId)
        .asFlow()
        .mapToList()

    fun getGroupByLink(link: String) = database.groupQueries.get(link).asFlow().mapToOne()

    fun isDownloaded(group: Group): Boolean =
        database.contentQueries
            .getByGroupLink(group.link)
            .executeAsList()
            .size == group.total

    fun updateLastRead(group: Group, lastRead: Int) {
        if (database.groupQueries.get(group.link).executeAsOneOrNull() == null)
            throw IllegalArgumentException("$this chapter group does not exists")

        database.groupQueries.updateLastRead(lastRead, group.link)
    }

    suspend fun getGroups(
        book: Book,
        refresh: Boolean = false
    ): Flow<List<Group>> {
        val dbGroups = database.bookQueries.chapters(book.id).executeAsList()
        if (database.bookQueries.getUndergroundById(book.id).executeAsOneOrNull() == null)
            fetchWebNovelGroups(book, refresh, dbGroups)
        else
            fetchUndergroundGroups(book, refresh, dbGroups)

        return database.bookQueries.chapters(book.id).asFlow().mapToList()
    }

    private suspend fun fetchWebNovelGroups(
        book: Book,
        refresh: Boolean,
        dbGroups: List<Group>
    ) {
        if (refresh || dbGroups.isEmpty()) {
            val remoteWebNovelChapters =
                database.bookQueries.getWebNovelById(book.id).executeAsOne()
                    .let { webNovelApi.getChapter(it.id, it.link) }
                    ?.filter { !it.premium }
                    ?.map { it.toGroup(book) }
                    ?: listOf()

            database.groupQueries.transaction {
                for (chapter in remoteWebNovelChapters)
                    database.groupQueries.insert(chapter)
            }
        }
    }

    private suspend fun fetchUndergroundGroups(
        book: Book,
        refresh: Boolean,
        dbGroups: List<Group>
    ) = coroutineScope {
        if (refresh || dbGroups.isEmpty()) {
            val dbBook = database.bookQueries.getUndergroundById(book.id).executeAsOne()
            val remoteGroupsDeferred = async {
                undergroundApi
                    .getChapters(dbBook.undergroundId)
                    .map { it.toGroup(book) }
            }

            val remoteWebNovelChaptersDeferred = if (
                libraryPreferences.checkForWebNovel.get()
                || dbGroups.isEmpty()
                || !dbGroups.any { "WebNovel" in it.source }
            ) async {
                try {
                    metaRepo.getBook(book, refresh).first()
                        ?.let { webNovelApi.getChapter(it.id, it.link) }
                        ?.filter { !it.premium }
                        ?.map { it.toGroup(book) }
                        ?: listOf()
                } catch (e: Exception) {
                    e(e)
                    listOf()
                }
            } else async { listOf() }

            val remoteGroups = remoteGroupsDeferred.await()
            val remoteChapters = remoteGroups.associateBy { it.firstChapter }
            val dbGroupsToUpdate = dbGroups
                .filter { it.firstChapter.toInt() in remoteChapters.keys }
                .filter { it.lastChapter.toInt() != remoteChapters[it.firstChapter.toInt()]?.lastChapter }

            val remoteWebNovelChapters = remoteWebNovelChaptersDeferred.await()
            database.groupQueries.transaction {
                for (group in dbGroupsToUpdate) {
                    val remoteGroup = remoteChapters[group.firstChapter.toInt()]!!
                    // We need to delete all the previous chapters to make sure foreign key
                    // doesn't fail
                    database.contentQueries.deleteByGroupLink(group.link)
                    database.groupQueries.update(
                        link = group.link,
                        updatedText = remoteGroup.text,
                        updatedLink = remoteGroup.link
                    )
                }

                // Due to INSERT OR IGNORE, we can ignore same entries
                for (group in remoteGroups)
                    database.groupQueries.insert(group)

                for (chapter in remoteWebNovelChapters)
                    database.groupQueries.insert(chapter)
            }
        }
    }

    private fun UndergroundGroup.toGroup(book: Book) = BaseGroup(book.id, text, link, 0)

    private fun WNChapterRemote.toGroup(book: Book) =
        BaseGroup(book.id, index.toString(), link, 0)

    private val BaseGroup.firstChapter: Int
        get() = text.split("-").first().trim().toInt()

    private val BaseGroup.lastChapter: Int
        get() = text.split("-").last().trim().toInt()

}
