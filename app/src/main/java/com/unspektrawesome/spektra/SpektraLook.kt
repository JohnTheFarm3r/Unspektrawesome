package com.unspektrawesome.spektra

object SpektraLook {
    val formats = listOf(
        "Standard 8", "Super 8", "Standard 16", "Super 16",
        "Standard 35", "Super 35", "Standard 65", "IMAX 70",
    )
    val diffusionFamilies = listOf("Glimmerglass", "Black Pro-Mist", "Pro-Mist", "CineBloom")

    fun stock(params: SpektraFilmParams, index: Int): SpektraFilmParams {
        require(index in SpektraFilmParams.FILM_LABELS.indices)
        return params.copy(film = index)
    }

    fun paper(params: SpektraFilmParams, index: Int): SpektraFilmParams {
        require(index in SpektraFilmParams.PAPER_LABELS.indices)
        return params.copy(paper = index)
    }

    fun format(params: SpektraFilmParams, index: Int): SpektraFilmParams {
        require(index in formats.indices)
        if (index == params.filmFormat) return params
        val amounts = params.formatGrainAmounts + (params.filmFormat to formatGrainAmount(params, params.filmFormat))
        val amount = amounts[index] ?: 1f
        val selected = params.copy(filmFormat = index, formatGrainAmounts = amounts)
        return if (params.grainModel == 2) selected.copy(grainSynthesisAmount = amount)
        else selected.copy(grainAmount = amount)
    }

    fun formatGrainAmount(params: SpektraFilmParams, index: Int): Float =
        if (index == params.filmFormat) {
            if (params.grainModel == 2) params.grainSynthesisAmount else params.grainAmount
        } else params.formatGrainAmounts[index] ?: 1f

    fun formatGrain(params: SpektraFilmParams, index: Int, amount: Float): SpektraFilmParams {
        require(amount.isFinite() && amount in 0f..2f)
        val selected = format(params, index)
        val updated = selected.copy(formatGrainAmounts = selected.formatGrainAmounts + (index to amount))
        return if (updated.grainModel == 2) updated.copy(grainSynthesisAmount = amount)
        else updated.copy(grainAmount = amount)
    }

    fun grainAmount(params: SpektraFilmParams): Float = when {
        !params.grainEnabled -> 0f
        params.grainModel == 2 -> params.grainSynthesisAmount
        else -> params.grainAmount
    }

    fun grain(params: SpektraFilmParams, amount: Float): SpektraFilmParams {
        require(amount.isFinite() && amount in 0f..2f)
        return if (params.grainModel == 2) {
            params.copy(grainEnabled = amount > 0f, grainSynthesisAmount = amount)
        } else {
            params.copy(grainEnabled = amount > 0f, grainAmount = amount)
        }
    }

    fun halation(params: SpektraFilmParams, amount: Float): SpektraFilmParams {
        require(amount.isFinite() && amount in 0f..4f)
        return params.copy(halationEnabled = amount > 0f, halationAmount = amount)
    }

    fun diffusion(params: SpektraFilmParams, amount: Float): SpektraFilmParams {
        require(amount.isFinite() && amount in 0f..2f)
        return params.copy(cameraDiffusionEnabled = amount > 0f, cameraDiffusionStrength = amount)
    }

    fun diffusionAmount(params: SpektraFilmParams, family: Int): Float =
        if (family == params.cameraDiffusionFamily) params.cameraDiffusionStrength
        else params.diffusionAmounts[family] ?: 0.5f

    fun diffusionFamily(params: SpektraFilmParams, family: Int): SpektraFilmParams {
        require(family in diffusionFamilies.indices)
        if (family == params.cameraDiffusionFamily) return params.copy(cameraDiffusionEnabled = true)
        val amounts = params.diffusionAmounts +
            (params.cameraDiffusionFamily to diffusionAmount(params, params.cameraDiffusionFamily))
        return params.copy(cameraDiffusionEnabled = true, cameraDiffusionFamily = family,
            diffusionAmounts = amounts, cameraDiffusionStrength = amounts[family] ?: 0.5f)
    }

    fun diffusion(params: SpektraFilmParams, family: Int, amount: Float): SpektraFilmParams {
        require(amount.isFinite() && amount in 0f..2f)
        val selected = diffusionFamily(params, family)
        return selected.copy(cameraDiffusionStrength = amount,
            diffusionAmounts = selected.diffusionAmounts + (family to amount))
    }

    fun stockKind(index: Int): String = when (index) {
        SpektraFilmParams.TRI_X_INDEX -> "B&W reversal"
        in 8..12 -> "Motion picture negative"
        in 16..19 -> "Color reversal"
        else -> "Color negative"
    }
}
