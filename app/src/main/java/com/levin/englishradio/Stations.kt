package com.levin.englishradio

data class Station(
    val id: String,
    val name: String,
    val subtitle: String,
    /** Stream URLs in preference order (verified working). */
    val urls: List<String>,
    /** Query used to find replacement URLs in the Radio Browser directory. */
    val healName: String,
)

val DEFAULT_STATIONS: List<Station> = listOf(
    Station(
        id = "bbc_ws",
        name = "BBC World Service",
        subtitle = "International news \u2022 UK \u2022 clear standard English",
        urls = listOf(
            "https://as-hls-ww-live.akamaized.net/pool_87948813/live/ww/bbc_world_service/bbc_world_service.isml/bbc_world_service-audio%3d128000.norewind.m3u8",
            "https://stream.live.vc.bbcmedia.co.uk/bbc_world_service",
            "https://a.files.bbci.co.uk/media/live/manifesto/audio/simulcast/hls/nonuk/sbr_low/ak/bbc_world_service.m3u8",
        ),
        healName = "BBC World Service",
    ),
    Station(
        id = "npr",
        name = "NPR",
        subtitle = "US news & culture \u2022 American English",
        urls = listOf(
            "https://npr-ice.streamguys1.com/live.mp3",
            "https://npr-ice.streamguys1.com/live.aac",
        ),
        healName = "NPR Program Stream",
    ),
    Station(
        id = "bbc_r4",
        name = "BBC Radio 4",
        subtitle = "Drama, comedy & news \u2022 British spoken word",
        urls = listOf(
            "https://as-hls-ww-live.akamaized.net/pool_55057080/live/ww/bbc_radio_fourfm/bbc_radio_fourfm.isml/bbc_radio_fourfm-audio%3d128000.norewind.m3u8",
            "https://as-hls-ww-live.akamaized.net/pool_55057080/live/ww/bbc_radio_fourfm/bbc_radio_fourfm.isml/bbc_radio_fourfm-audio%3d96000.norewind.m3u8",
            "https://as-hls-ww-live.akamaized.net/pool_55057080/live/ww/bbc_radio_fourfm/bbc_radio_fourfm.isml/bbc_radio_fourfm-audio%3d320000.norewind.m3u8",
        ),
        healName = "BBC Radio 4",
    ),
    Station(
        id = "lbc",
        name = "LBC London",
        subtitle = "UK news talk \u2022 clear London English",
        urls = listOf(
            "https://media-sov.musicradio.com/LBCLondon",
            "https://media-ice.musicradio.com/LBCLondonMP3Low",
        ),
        healName = "LBC London",
    ),
    Station(
        id = "fox",
        name = "Fox News Radio",
        subtitle = "24/7 US news \u2022 American English",
        urls = listOf(
            "https://live.amperwave.net/direct/foxnewsradio-foxnewsradioaac-imc?source=fnr.web",
        ),
        healName = "Fox News Radio",
    ),
    Station(
        id = "abc_rn",
        name = "ABC Radio National",
        subtitle = "Ideas & documentaries \u2022 calm Australian English",
        urls = listOf(
            "https://streaming.abc-cdn.net.au/audio/hls/rnnt.m3u8?source=web",
        ),
        healName = "ABC Radio National",
    ),
    Station(
        id = "cgtn",
        name = "CGTN Radio",
        subtitle = "24/7 English news \u2022 Beijing, China",
        urls = listOf(
            "https://sk.cri.cn/am846.m3u8",
        ),
        healName = "CGTN Radio",
    ),
)

fun stationById(id: String?): Station? = DEFAULT_STATIONS.firstOrNull { it.id == id }
