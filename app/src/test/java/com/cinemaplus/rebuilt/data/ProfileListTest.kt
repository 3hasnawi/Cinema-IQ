package com.cinemaplus.rebuilt.data

import org.junit.Assert.*
import org.junit.Test

class ProfileListTest {
    @Test fun emptyOrDamagedStorageStillYieldsMainProfile() {
        assertEquals(listOf(ProfileList.MAIN), ProfileList.decode(null).map { it.id })
        assertEquals(listOf(ProfileList.MAIN), ProfileList.decode("not json").map { it.id })
    }

    @Test fun roundTripKeepsMainFirstAndOthersInOrder() {
        val list = ProfileList.add(ProfileList.decode(null), "ضيف", "p1")!!
        val back = ProfileList.decode(ProfileList.encode(list))
        assertEquals(listOf(ProfileList.MAIN, "p1"), back.map { it.id })
        assertEquals("ضيف", back[1].name)
    }

    @Test fun blankNamesDuplicateIdsAndOverflowAreRejected() {
        val base = ProfileList.decode(null)
        assertNull(ProfileList.add(base, "   ", "p1"))
        assertNull(ProfileList.add(base, "x", ProfileList.MAIN))
        var list = base
        repeat(ProfileList.MAX - 1) { list = ProfileList.add(list, "u$it", "p$it")!! }
        assertNull(ProfileList.add(list, "extra", "pz"))
    }

    @Test fun mainProfileCannotBeRemoved() {
        val list = ProfileList.add(ProfileList.decode(null), "ضيف", "p1")!!
        assertEquals(list, ProfileList.remove(list, ProfileList.MAIN))
        assertEquals(listOf(ProfileList.MAIN), ProfileList.remove(list, "p1").map { it.id })
    }

    @Test fun unknownActiveProfileFallsBackToMain() {
        val list = ProfileList.decode(null)
        assertEquals(ProfileList.MAIN, ProfileList.resolveActive(list, "gone"))
        assertEquals(ProfileList.MAIN, ProfileList.resolveActive(list, null))
    }

    @Test fun mainProfileKeepsTheOriginalPreferencesFile() {
        assertEquals("cinema_library", ProfileList.prefsName(ProfileList.MAIN))
        assertEquals("cinema_library_p1", ProfileList.prefsName("p1"))
    }

    @Test fun namesAreTrimmedAndCapped() {
        val list = ProfileList.add(ProfileList.decode(null), "  " + "ا".repeat(40) + "  ", "p1")!!
        assertEquals(16, list[1].name.length)
    }
    @Test fun legacyProfilesAcquireADefaultAvatarWithoutChangingIdentity() {
        val result = ProfileList.decode("""[{"id":"main","name":"Viewer","color":123}]""")
        assertEquals("main", result[0].id)
        assertEquals("avatar-0", result[0].avatar)
        assertEquals("cinema_library", ProfileList.prefsName(result[0].id))
    }
    @Test fun editsPreserveIdentityColorAndOtherProfiles() {
        val list = ProfileList.add(ProfileList.decode(null), "Guest", "guest")!!
        val edited = ProfileList.update(list, "guest", "  New name  ", "avatar-4")!!
        assertEquals(list[0], edited[0])
        assertEquals("guest", edited[1].id)
        assertEquals(list[1].color, edited[1].color)
        assertEquals("New name", edited[1].name)
        assertEquals("avatar-4", edited[1].avatar)
        assertEquals("cinema_library_guest", ProfileList.prefsName(edited[1].id))
    }
    @Test fun importedAndBuiltInAvatarsRoundTrip() {
        val list = listOf(ProfileList.default().copy(avatar = "/private/profile-images/photo.png"),
            Profile("guest", "Guest", 123, "avatar-5"))
        assertEquals(list, ProfileList.decode(ProfileList.encode(list)))
    }
    @Test fun invalidEditsAreRejectedAndMainCanBeRenamed() {
        val list = ProfileList.decode(null)
        assertNull(ProfileList.update(list, "main", " ", "avatar-0"))
        assertNull(ProfileList.update(list, "unknown", "Name", "avatar-0"))
        assertNull(ProfileList.update(list, "main", "Name", ""))
        assertEquals("New", ProfileList.update(list, "main", "New", "avatar-2")!![0].name)
        assertEquals(list, ProfileList.remove(list, "main"))
    }
}
