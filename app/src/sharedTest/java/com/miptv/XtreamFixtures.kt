package com.miptv

/** Anonymised `player_api.php` responses, as providers actually send them: strings for numbers, `{}` for empty lists, missing fields. */
object XtreamFixtures {
    const val ACCOUNT_ACTIVE = """{"user_info":{"username":"u-test","password":"p-test","message":"","auth":1,"status":"Active","exp_date":"1798761600","is_trial":"0","active_cons":"1","created_at":"1700000000","max_connections":"2","allowed_output_formats":["m3u8","ts","rtmp"]},"server_info":{"url":"portal.example","port":"8080","https_port":"8443","server_protocol":"http","rtmp_port":"1935","timezone":"Europe/Paris","timestamp_now":1761734400,"time_now":"2026-10-29 12:00:00"}}"""

    /** Lifetime account, numbers as numbers, HLS only, no server block. */
    const val ACCOUNT_UNLIMITED = """{"user_info":{"auth":true,"status":"Active","exp_date":null,"is_trial":0,"active_cons":0,"max_connections":1,"allowed_output_formats":["m3u8"]}}"""

    const val ACCOUNT_REFUSED = """{"user_info":{"auth":0,"status":"Disabled","message":"invalid credentials"}}"""

    const val ACCOUNT_EXPIRED = """{"user_info":{"auth":"1","status":"Expired","exp_date":"1735732800","active_cons":"0","max_connections":"1"}}"""

    const val ACCOUNT_BANNED = """{"user_info":{"auth":1,"status":"Banned","exp_date":"1798761600"}}"""

    const val ACCOUNT_SATURATED = """{"user_info":{"auth":1,"status":"Active","exp_date":"1798761600","active_cons":"2","max_connections":"2"}}"""

    const val LIVE_CATEGORIES = """[{"category_id":"1","category_name":"Généralistes","parent_id":0},{"category_id":"7","category_name":"Sport","parent_id":0},{"category_id":"9","category_name":"","parent_id":0},{"category_name":"Sans identifiant"}]"""

    const val LIVE_STREAMS = """[
      {"num":1,"name":"TF1 HD","stream_type":"live","stream_id":101,"stream_icon":"http://img.example/tf1.png","epg_channel_id":"tf1.fr","added":"1700000000","category_id":"1","category_ids":[1],"custom_sid":"","tv_archive":0,"direct_source":"","tv_archive_duration":0},
      {"num":2,"name":"France 2","stream_type":"live","stream_id":"102","stream_icon":"","epg_channel_id":null,"category_id":null,"category_ids":[7]},
      {"num":3,"name":"  Orpheline  ","stream_id":103,"category_id":"42"},
      {"num":4,"name":"Sans catégorie du tout","stream_id":104},
      {"num":5,"name":"Doublon","stream_id":101,"category_id":"7"},
      {"num":6,"name":"Sans identifiant"},
      {"num":7,"name":null,"stream_id":107,"category_id":"7","stream_icon":"ftp://x/logo.png"}
    ]"""

    const val EMPTY_OBJECT_LIST = "{}"

    /** Lists keyed by id, as some panels serialise them. */
    const val KEYED_CATEGORIES = """{"3":{"category_id":3,"category_name":"Action"},"5":{"category_id":5,"category_name":"Comédie"}}"""

    const val VOD_STREAMS = """[
      {"num":1,"name":"Le Voyage (2019)","stream_type":"movie","stream_id":5001,"stream_icon":"http://img.example/voyage.jpg","rating":"7.5","rating_5based":3.75,"added":"1699999000","category_id":"3","category_ids":[3],"container_extension":"mkv","custom_sid":"","direct_source":""},
      {"num":2,"name":"Nuit blanche","title":"Nuit blanche","year":"2021","stream_type":"movie","stream_id":"5002","stream_icon":null,"rating":"N/A","added":"","category_id":"","container_extension":"","release_date":"2021-03-12","plot":"Une nuit sans sommeil."},
      {"num":3,"name":"Sans conteneur","stream_id":5003,"rating":0,"category_ids":[5]},
      {"num":4,"name":"Pas de stream_id"}
    ]"""

    const val VOD_INFO = """{"info":{"kinopoisk_url":"","tmdb_id":"123","name":"Le Voyage","o_name":"The Journey","cover_big":"http://img.example/voyage-big.jpg","movie_image":"http://img.example/voyage.jpg","release_date":"2019-06-14","episode_run_time":"118","youtube_trailer":"","director":"A. B.","actors":"C. D., E. F.","cast":"C. D., E. F.","description":"","plot":"Deux amis traversent un pays.","age":"","mpaa_rating":"","rating_count_kinopoisk":0,"country":"France","genre":"Aventure","backdrop_path":["http://img.example/bd.jpg"],"duration_secs":7080,"duration":"01:58:00","bitrate":2500,"rating":"7.6","releasedate":"2019-06-14","subtitles":[]},"movie_data":{"stream_id":5001,"name":"Le Voyage (2019)","added":"1699999000","category_id":"3","container_extension":"mkv","custom_sid":"","direct_source":""}}"""

    /** Unknown film: PHP serialises the empty blocks as arrays. */
    const val VOD_INFO_UNKNOWN = """{"info":[],"movie_data":[]}"""

    const val SERIES_LIST = """[
      {"num":1,"name":"Horizons","series_id":77,"cover":"http://img.example/horizons.jpg","plot":"Une expédition.","cast":"","director":"","genre":"Drame","releaseDate":"2019-01-10","last_modified":"1700000500","rating":"8","rating_5based":4,"backdrop_path":[],"youtube_trailer":"","episode_run_time":"45","category_id":"12","category_ids":[12]},
      {"num":2,"name":"La ville bleue","series_id":"78","cover":"","plot":null,"releaseDate":"","last_modified":"0","rating":"","episode_run_time":"0","category_id":null},
      {"num":3,"name":"Sans identifiant"}
    ]"""

    const val SERIES_INFO = """{
      "seasons":[
        {"air_date":"2019-01-10","episode_count":2,"id":1,"name":"Saison 1","overview":"","season_number":1,"cover":"http://img.example/h-s1.jpg","cover_big":""},
        {"air_date":"2020-01-10","episode_count":"1","id":2,"name":"","season_number":"2","cover":""},
        {"air_date":"","episode_count":0,"id":3,"name":"Spéciaux","season_number":0}
      ],
      "info":{"name":"Horizons","cover":"http://img.example/horizons-big.jpg","plot":"Une expédition au long cours.","cast":"","director":"","genre":"Drame","releaseDate":"2019-01-10","last_modified":"1700000500","rating":"8.2","rating_5based":4.1,"backdrop_path":[],"youtube_trailer":"","episode_run_time":"45","category_id":"12"},
      "episodes":{
        "1":[
          {"id":"9001","episode_num":1,"title":"Horizons - S01E01 - Départ","container_extension":"mkv","info":{"tmdb_id":1,"releasedate":"2019-01-10","plot":"Le départ.","duration_secs":2700,"duration":"00:45:00","movie_image":"http://img.example/h-101.jpg","bitrate":0,"rating":8,"season":1},"custom_sid":"","added":"1700000000","season":1,"direct_source":""},
          {"id":9002,"episode_num":"2","title":"","container_extension":"mp4","info":{"duration_secs":0,"duration":"00:47:30","movie_image":""},"season":"1"}
        ],
        "2":[
          {"id":"9003","episode_num":1,"title":"Retour","container_extension":"","info":[],"season":2}
        ]
      }
    }"""

    /** Episodes as an array of arrays, no season list, season taken from the episode. */
    const val SERIES_INFO_ARRAYS = """{"seasons":[],"info":{"name":"La ville bleue"},"episodes":[[{"id":"9101","episode_num":1,"title":"Un","season":3,"container_extension":"mkv"},{"id":"9102","episode_num":2,"title":"Deux","season":3}],[{"id":"9103","episode_num":1,"title":"Trois","season":4}]]}"""

    const val HTML_LOGIN_PAGE = "<!DOCTYPE html><html><head><title>Login</title></head><body>Forbidden</body></html>"

    /** [count] films of modest size, like a real VOD catalogue of a large provider. */
    fun manyMovies(count: Int): String = buildString(count * 220) {
        append('[')
        for (i in 1..count) {
            if (i > 1) append(',')
            append("""{"num":""").append(i).append(""","name":"Film n° """).append(i).append(""" (20""").append(10 + i % 15).append(""")","stream_type":"movie","stream_id":""")
                .append(100_000 + i).append(""","stream_icon":"http://img.example/""").append(i).append(""".jpg","rating":"""").append(i % 10).append(""".""").append(i % 10)
                .append("""","rating_5based":3,"added":"""").append(1_700_000_000 + i).append("""","category_id":"""").append(i % 50).append("""","category_ids":[""").append(i % 50)
                .append("""],"container_extension":"mp4","custom_sid":"","direct_source":""}""")
        }
        append(']')
    }
}
