package com.lagradost.cloudstream3.providers

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class DiziFilmSiteleriPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FilmMakinesi())
        registerMainAPI(FullHDFilmizlesene())
        registerMainAPI(KultFilmler())
        registerMainAPI(SezonlukDizi())
        registerMainAPI(Sinewix())
        registerMainAPI(DizipalProvider())
        registerMainAPI(FilmizleChProvider())
        registerMainAPI(DiziSolProvider())
        registerMainAPI(DiziFilmLifeProvider())
        registerMainAPI(FilmizleNowProvider())
        registerMainAPI(SetFilmizleProvider())
        registerMainAPI(DizirollProvider())
        
        registerExtractorAPI(CloseLoad())
        registerExtractorAPI(RapidVid())
        registerExtractorAPI(TRsTX())
        registerExtractorAPI(VidMoxy())
        registerExtractorAPI(Sobreatsesuyp())
        registerExtractorAPI(TurboImgz())
        registerExtractorAPI(TurkeyPlayer())
    }
}