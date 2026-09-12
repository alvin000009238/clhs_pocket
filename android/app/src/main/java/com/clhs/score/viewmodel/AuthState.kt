package com.clhs.score.viewmodel

sealed interface AuthState {
    data object Restoring : AuthState
    data object Guest : AuthState
    data object Authenticating : AuthState
    data class Authenticated(val generation: Long) : AuthState
}
