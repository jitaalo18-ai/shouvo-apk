package com.videoeditor.app.data

/** Date means import date; duration means media length. Stable ties prevent row jumping. */
enum class LibrarySort(val label: String) {
    NAME_ASC("Name: A–Z"), NAME_DESC("Name: Z–A"),
    NEWEST("Date added: newest first"), OLDEST("Date added: oldest first"),
    SHORTEST("Duration: shortest first"), LONGEST("Duration: longest first");

    fun apply(items: List<LibraryItemEntity>): List<LibraryItemEntity> {
        val byName = Comparator<LibraryItemEntity> { a,b -> a.name.compareTo(b.name, ignoreCase = true) }
        val comparator: Comparator<LibraryItemEntity> = when(this) {
            NAME_ASC -> byName
            NAME_DESC -> byName.reversed()
            NEWEST -> compareByDescending { it.addedAt }
            OLDEST -> compareBy { it.addedAt }
            SHORTEST -> compareBy { it.durationMs }
            LONGEST -> compareByDescending { it.durationMs }
        }
        return items.sortedWith(comparator.thenBy { it.id })
    }
}
