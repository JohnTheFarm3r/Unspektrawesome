#pragma once

#include "SpektraParameters.h"
#include "SpektraProfileCurves.h"

namespace spektrafilm {

inline RenderParams resolveMonochromeParams(const RenderParams &requested) {
  const auto *film = filmProfileCurves(requested.film);
  if (!film || film->channelCount != 1u) return requested;
  RenderParams params = requested;
  const RenderParams defaults;
  params.process = ProcessMode::ScanNegative;
  params.scanNegativeInvert = false;
  params.negativeBleachBypassAmount = 0.0f;
  params.printDiffusionEnabled = false;
  if (params.renderOutput == RenderOutputMode::PrintLogRaw ||
      params.renderOutput == RenderOutputMode::PrintDensityCmy) {
    params.renderOutput = RenderOutputMode::FinalPreview;
  }
  // A single emulsion has self-inhibition, with no colour-layer coupling.
  if (params.dirCouplersGammaSameLayerR == defaults.dirCouplersGammaSameLayerR) {
    params.dirCouplersGammaSameLayerR = film->dirGammaSameLayerRgb[0];
  }
  params.dirCouplersGammaSameLayerG = params.dirCouplersGammaSameLayerR;
  params.dirCouplersGammaSameLayerB = params.dirCouplersGammaSameLayerR;
  params.dirCouplersInhibitionInterlayer = 0.0f;
  params.dirCouplersGammaRToG = params.dirCouplersGammaRToB = 0.0f;
  params.dirCouplersGammaGToR = params.dirCouplersGammaGToB = 0.0f;
  params.dirCouplersGammaBToR = params.dirCouplersGammaBToG = 0.0f;
  // The first density lane is the silver image; do not average independent colour noise into it.
  params.grainSaturation = 1.0f;
  if (const float *grain = film->monochromeGrain) {
    params.grainParticleAreaUm2 *= grain[0] / defaults.grainParticleAreaUm2;
    params.grainParticleScaleR /= defaults.grainParticleScaleR;
    params.grainParticleScaleLayer0 *= grain[3] / defaults.grainParticleScaleLayer0;
    params.grainParticleScaleLayer1 *= grain[4] / defaults.grainParticleScaleLayer1;
    params.grainParticleScaleLayer2 *= grain[5] / defaults.grainParticleScaleLayer2;
    if (params.grainDensityMinR == defaults.grainDensityMinR) params.grainDensityMinR = grain[1];
    if (params.grainUniformityR == defaults.grainUniformityR) params.grainUniformityR = grain[2];
  }
  return params;
}

}
