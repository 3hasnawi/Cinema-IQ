package com.cinemaplus.rebuilt.data.iptv

import org.junit.Assert.*
import org.junit.Test

class IptvSourceFocusTest {
    @Test fun m3uDoesNotRouteThroughHiddenXtreamFieldsOrDisabledConnect() {
        val flow = IptvSourceFocus.order(IptvKind.M3U, false)
        assertEquals(listOf(IptvSourceControl.Type, IptvSourceControl.Name, IptvSourceControl.Address,
            IptvSourceControl.Http, IptvSourceControl.Cancel), flow)
        assertEquals(IptvSourceControl.Http, IptvSourceFocus.move(IptvSourceControl.Address, 1, IptvKind.M3U, false))
        assertEquals(IptvSourceControl.Cancel, IptvSourceFocus.move(IptvSourceControl.Http, 1, IptvKind.M3U, false))
    }
    @Test fun xtreamTraversesAddressUserPasswordOutputBeforeSaving() {
        val flow = IptvSourceFocus.order(IptvKind.Xtream, true)
        val inputs = flow.subList(flow.indexOf(IptvSourceControl.Address), flow.indexOf(IptvSourceControl.Http))
        assertEquals(listOf(IptvSourceControl.Address, IptvSourceControl.User, IptvSourceControl.Password, IptvSourceControl.Output), inputs)
        assertEquals(IptvSourceControl.Connect, IptvSourceFocus.move(IptvSourceControl.Http, 1, IptvKind.Xtream, true))
        assertEquals(IptvSourceControl.Password, IptvSourceFocus.move(IptvSourceControl.Output, -1, IptvKind.Xtream, true))
    }
    @Test fun tabCyclesWhileArrowNavigationStaysInsideFormBoundaries() {
        assertEquals(IptvSourceControl.Type, IptvSourceFocus.move(IptvSourceControl.Cancel, 1, IptvKind.M3U, true, true))
        assertEquals(IptvSourceControl.Cancel, IptvSourceFocus.move(IptvSourceControl.Type, -1, IptvKind.Xtream, false, true))
        assertEquals(IptvSourceControl.Type, IptvSourceFocus.move(IptvSourceControl.Type, -1, IptvKind.M3U, true))
        assertEquals(IptvSourceControl.Cancel, IptvSourceFocus.move(IptvSourceControl.Cancel, 1, IptvKind.Xtream, false))
    }
}
