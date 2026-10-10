package com.example.myapplicationkoG

/** In-memory holder for the design selected for sharing. */
object ShareDesignSession {
    @Volatile var selected: DesignRow? = null

    fun clear() { selected = null }
}
