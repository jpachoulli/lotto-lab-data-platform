package com.jeremybernsdorff.lottolab.dataplatform.acquisition.current

class CurrentDrawSourceRegistry(private val sources: List<OfficialDrawSource>) {
    init { require(sources.map { it.gameId to it.sourceId }.distinct().size == sources.size) }
    fun sourcesFor(gameId: String): List<OfficialDrawSource> = sources.filter { it.gameId == gameId }

    companion object {
        fun production(http: OfficialDrawHttpClient = OfficialDrawHttpClient()): CurrentDrawSourceRegistry =
            CurrentDrawSourceRegistry(listOf(
                NyPowerballCurrentDrawSource(http), DelawarePowerballCurrentDrawSource(http),
                NyMegaMillionsCurrentDrawSource(http), DelawareMegaMillionsCurrentDrawSource(http),
                MuslLottoAmericaCurrentDrawSource(http), IowaLottoAmericaCurrentDrawSource(http)
            ))
    }
}
