package com.example.myapplicationkoG.di

import android.content.Context
import com.example.myapplicationkoG.inference.ClothingInferencePipeline

object ServiceLocator {
    @Volatile private var inference: ClothingInferencePipeline? = null

    fun inferencePipeline(context: Context): ClothingInferencePipeline {
        return inference ?: synchronized(this) {
            inference ?: ClothingInferencePipeline(context.applicationContext).also { inference = it }
        }
    }
}