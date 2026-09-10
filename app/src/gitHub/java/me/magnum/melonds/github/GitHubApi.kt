package me.magnum.melonds.github

import me.magnum.melonds.github.dtos.ReleaseDto
import retrofit2.http.GET

interface GitHubApi {
    // [KHMM] Update checks target the Melon Mix fork's releases, not upstream's
    @GET("/repos/Nireves333/melonMix-android/releases/latest")
    suspend fun getLatestRelease(): ReleaseDto

    @GET("/repos/Nireves333/melonMix-android/releases/tags/nightly-release")
    suspend fun getLatestNightlyRelease(): ReleaseDto
}