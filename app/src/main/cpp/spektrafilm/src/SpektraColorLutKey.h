#pragma once

#include "SpektraParameters.h"
#include <array>
#include <tuple>

namespace spektrafilm {

// Includes the shared range probes and all four baked colour transforms.
// Exposure and spatial effects are applied after lookup and do not change the tables.
inline auto fourStageColorLutKey(const RenderParams &p, bool bakeDir = false) {
  const auto couplers = bakeDir ? std::array<float, 12>{
    p.dirCouplersAmount, p.dirCouplersInhibitionSameLayer, p.dirCouplersInhibitionInterlayer,
    p.dirCouplersGammaSameLayerR, p.dirCouplersGammaSameLayerG, p.dirCouplersGammaSameLayerB,
    p.dirCouplersGammaRToG, p.dirCouplersGammaRToB, p.dirCouplersGammaGToR,
    p.dirCouplersGammaGToB, p.dirCouplersGammaBToR, p.dirCouplersGammaBToG
  } : std::array<float, 12>{};
  return std::make_tuple(
    p.film, p.paper, p.process, p.scanNegativeInvert, p.renderOutput,
    p.rgbToRawMethod, p.inputColorSpace, p.outputColorSpace, p.outputRole,
    colorAdaptationFlags(p),
    p.cameraUvFilterEnabled, p.cameraUvCutNm, p.cameraIrFilterEnabled, p.cameraIrCutNm,
    p.filmPushPullMode, p.filmPushPullStops, p.filmGamma, bakeDir, couplers,
    p.printPushPullStops, p.printGamma, p.printShadowShape, p.printHighlightShape,
    p.negativeBleachBypassAmount, p.negativeLeucoCyanCoupling, p.printBleachBypassAmount,
    p.filterC, p.filterMShift, p.filterYShift, p.printTiming, p.printExposureEv,
    p.preflashExposure, p.preflashMFilterShift, p.preflashYFilterShift,
    p.printerLightsR, p.printerLightsG, p.printerLightsB, p.printerLightsGang, p.printerLightCalibration,
    p.scannerEnabled, p.scannerWhiteCorrection, p.scannerBlackCorrection, p.scannerWhiteLevel, p.scannerBlackLevel,
    p.hdrPreset, p.hdrTransfer, p.hdrReferenceWhiteNits, p.hdrPeakNits, p.hdrExposureEv, p.hdrToneMapping
  );
}

using FourStageColorLutKey = decltype(fourStageColorLutKey(RenderParams{}));

}
