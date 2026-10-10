package com.litetv.app.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogueDiffTest {

    private val channelA = Channel("id1", "News 24", "https://logo1.png", "News", "https://stream1.m3u8")
    private val channelB = Channel("id2", "Sports Live", "https://logo2.png", "Sports", "https://stream2.m3u8")
    private val channelC = Channel("id3", "Movies Now", "https://logo3.png", "Movies", "https://stream3.m3u8")

    @Test
    fun compute_detectsAdditions() {
        val oldList = listOf(channelA)
        val newList = listOf(channelA, channelB)

        val diff = CatalogueDiff.compute(oldList, newList)

        assertTrue(diff.hasChanges)
        assertEquals(1, diff.added.size)
        assertEquals(channelB, diff.added[0])
        assertEquals(0, diff.removed.size)
        assertEquals(0, diff.renamed.size)
    }

    @Test
    fun compute_detectsRemovals() {
        val oldList = listOf(channelA, channelB)
        val newList = listOf(channelA)

        val diff = CatalogueDiff.compute(oldList, newList)

        assertTrue(diff.hasChanges)
        assertEquals(0, diff.added.size)
        assertEquals(1, diff.removed.size)
        assertEquals(channelB, diff.removed[0])
    }

    @Test
    fun compute_detectsRenames() {
        val renamedA = Channel("id1", "News 24 HD", "https://logo1.png", "News", "https://stream1.m3u8")
        val oldList = listOf(channelA)
        val newList = listOf(renamedA)

        val diff = CatalogueDiff.compute(oldList, newList)

        assertTrue(diff.hasChanges)
        assertEquals(1, diff.renamed.size)
        assertEquals("News 24", diff.renamed[0].oldName)
        assertEquals("News 24 HD", diff.renamed[0].newName)
    }

    @Test
    fun compute_detectsCategoryChanges() {
        val movedB = Channel("id2", "Sports Live", "https://logo2.png", "Entertainment", "https://stream2.m3u8")
        val oldList = listOf(channelB)
        val newList = listOf(movedB)

        val diff = CatalogueDiff.compute(oldList, newList)

        assertTrue(diff.hasChanges)
        assertEquals(1, diff.categoryChanged.size)
        assertEquals("Sports", diff.categoryChanged[0].oldCategory)
        assertEquals("Entertainment", diff.categoryChanged[0].newCategory)
    }

    @Test
    fun compute_detectsMetadataUpdates() {
        val newLogoC = Channel("id3", "Movies Now", "https://newlogo.png", "Movies", "https://stream3.m3u8")
        val oldList = listOf(channelC)
        val newList = listOf(newLogoC)

        val diff = CatalogueDiff.compute(oldList, newList)

        assertTrue(diff.hasChanges)
        assertEquals(1, diff.metadataUpdated.size)
        assertEquals("https://logo3.png", diff.metadataUpdated[0].oldLogoUrl)
        assertEquals("https://newlogo.png", diff.metadataUpdated[0].newLogoUrl)
    }

    @Test
    fun compute_noChanges_returnsEmptyDiff() {
        val list = listOf(channelA, channelB, channelC)
        val diff = CatalogueDiff.compute(list, list)

        assertFalse(diff.hasChanges)
        assertEquals(0, diff.added.size)
        assertEquals(0, diff.removed.size)
        assertEquals(0, diff.renamed.size)
    }

    @Test
    fun summary_formatsCorrectly() {
        val diff = CatalogueDiff(
            added = listOf(channelA),
            removed = listOf(channelB),
            renamed = listOf(RenamedChannel("Old", "New", channelC))
        )
        val summary = diff.summary
        assertTrue(summary.contains("+1 added"))
        assertTrue(summary.contains("-1 removed"))
        assertTrue(summary.contains("1 renamed"))
    }
}
