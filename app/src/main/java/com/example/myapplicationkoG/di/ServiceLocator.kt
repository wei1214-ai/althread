package com.example.myapplicationkoG.di

import android.content.Context
import com.example.myapplicationkoG.inference.ClothingInferencePipeline

/** Application-scoped service holder. */
object ServiceLocator {
    @Volatile private var inference: ClothingInferencePipeline? = null

    /** Returns the shared inference pipeline, creating it on first use. */
    fun inferencePipeline(context: Context): ClothingInferencePipeline {
        return inference ?: synchronized(this) {
            inference ?: ClothingInferencePipeline(context.applicationContext).also { inference = it }
        }
    }
}