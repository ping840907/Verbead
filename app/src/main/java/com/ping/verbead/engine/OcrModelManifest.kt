package com.ping.verbead.engine

/**
 * Where the PP-OCRv6 LiteRT models are downloaded from, and their SHA-256 sums.
 *
 * The files are the output of tools/litert/convert_ocr.py (see tools/litert/README.md), attached to the GitHub release
 * [RELEASE_TAG] of this repository under exactly these names. To publish a new set, upload the new files to a new
 * release, bump [RELEASE_TAG] and replace the sums below (the release also carries a SHA256SUMS.txt with them).
 */
object OcrModelManifest {
    const val RELEASE_TAG = "ocr-models-v1"
    const val BASE_URL = "https://github.com/ping840907/Verbead/releases/download/$RELEASE_TAG/"

    val sha256: Map<String, String> = mapOf(
        "pp_ocrv6_small_det_480x960.tflite" to "15161e23eca5c986e2cb306eb008e54961e1230615b45485e418694526e2275a",
        "pp_ocrv6_small_det_640x640.tflite" to "08c31369d4c96d34ee45068c9f2a1c979ddff60767cc261a8483dcd9fc55629b",
        "pp_ocrv6_small_det_960x480.tflite" to "87ae196ee6770d98fb9ea2362add78f87def5572c84b0fd722bcbde0ef2b826b",
        "pp_ocrv6_small_dict.txt" to "ab078671bb49f06228eadccd34f1bb501e157f7a047095ffb943ba81512c77d1",
        "pp_ocrv6_small_rec_1280.tflite" to "d82d2520f62efed607684501a50efe6b2978f5e4f3bb0873667b062023a72626",
        "pp_ocrv6_small_rec_160.tflite" to "bec1d80394b3cbc2ebc550859a255c39d1d923ed0f58211176d2c451326c4890",
        "pp_ocrv6_small_rec_320.tflite" to "b2e6ce5dee3df91c100a4e0223fff56b9a6d8bf851a52117dc8fa5a0ade23f0e",
        "pp_ocrv6_small_rec_640.tflite" to "773da5767bd4444b20cfe45a127e38afb51904747d55d9038f62d2a230db2ee1",
        "pp_ocrv6_tiny_det_480x960.tflite" to "6dec016881659d211cbb53b89f08b827f9655ef30715708e10f4b4163495fe9a",
        "pp_ocrv6_tiny_det_640x640.tflite" to "61f54287a0ad8698daddf18f30af0fa23e48af8252df1b764e3d29c772c7d944",
        "pp_ocrv6_tiny_det_960x480.tflite" to "bb560411093c1e1aac7f752078c962727756305d05e83c35f3b2b13adb4f9dae",
        "pp_ocrv6_tiny_dict.txt" to "66170210bad538e83fff3c4a3867e547d6bf20b50d64b20347c4b913f3034ea1",
        "pp_ocrv6_tiny_rec_1024.tflite" to "48b0e129f93a519a19d2de96954d39bda5398d70d78a41c474497554fded0b4d",
        "pp_ocrv6_tiny_rec_128.tflite" to "a28f88d329518f8eeb5053d263a8bd7fbd92746a2fcb812b6ec52203925c7934",
        "pp_ocrv6_tiny_rec_1280.tflite" to "17f8fb594bc70b95e4909da3f23deb860e1adfa34a8ee6f995d9d932769dcb2e",
        "pp_ocrv6_tiny_rec_160.tflite" to "f8555649382404922d1f31b0feedac47c941435acc6f15dc7e85cc0d610de0e9",
        "pp_ocrv6_tiny_rec_192.tflite" to "1ebeb958a1b9c758032e273aed76a637fccf8c25d55719020318de6292002e2e",
        "pp_ocrv6_tiny_rec_224.tflite" to "8593ba6590858fd5a6b5801549f94319a905bff083d83e13cbe2ceadccc45f83",
        "pp_ocrv6_tiny_rec_256.tflite" to "02c12308490299ed98ce409dd10bca6414fc3afb03f82bd164c2dae1d49de377",
        "pp_ocrv6_tiny_rec_320.tflite" to "22e23ec05c5277091ace535b9d0f23c2187301773c03a24f244c53355fcb9cd7",
        "pp_ocrv6_tiny_rec_384.tflite" to "ba852d6a6009cef66074ee1e15e8001627d1a024bc731305e63de690e943facb",
        "pp_ocrv6_tiny_rec_448.tflite" to "6d8625a044aded687c8f250de8c79feebf0f0918de76dd9bdee51e0c9b0303aa",
        "pp_ocrv6_tiny_rec_512.tflite" to "bf96ca74d5a16ade0d2d59f68e67213f27b8045ec95138e8c4bcbe71fb09bfad",
        "pp_ocrv6_tiny_rec_64.tflite" to "66f31e022bc7bba09bac4aa5976691cbf97fab1c51d9a6e7f39a456cad63f1c6",
        "pp_ocrv6_tiny_rec_640.tflite" to "b6e7982324dda64d7b3f14fb4b58e832b836bfeb11b253f5557266a73290879a",
        "pp_ocrv6_tiny_rec_768.tflite" to "ed9a078c329f5e623068cb00fa10ceb5ad61558422b3ad1b58b5d27f05a96829",
        "pp_ocrv6_tiny_rec_896.tflite" to "ff7ae7e9ed78fc8bcc196ae8db65cbd1de5778659484431f17a62aa39b3e4bb7",
        "pp_ocrv6_tiny_rec_96.tflite" to "f1399194b4079710c422a80f0077774d18c0197c23c2db8d43eed10fe7faa169",
    )
}
