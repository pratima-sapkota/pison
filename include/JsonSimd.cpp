// JsonSimd.cpp

#include "JsonSimd.h"
#include <immintrin.h>
#include <stdint.h>

/*
 * Class:     JsonSimd
 * Method:    processChunk
 * Signature: ([B)[J
 */
JNIEXPORT jlongArray JNICALL
Java_JsonSimd_processChunk(JNIEnv* env, jobject /*self*/, jbyteArray inArr) {
    // 1) Pull the 32‐byte chunk from Java
    jbyte* in = env->GetByteArrayElements(inArr, nullptr);

    // 2) Load into an AVX2 register
    __m256i v = _mm256_loadu_si256(reinterpret_cast<const __m256i*>(in));

    // 3) Define our eight structural-character masks
    __m256i m_quote    = _mm256_set1_epi8(0x22);
    __m256i m_colon    = _mm256_set1_epi8(0x3A);
    __m256i m_escape   = _mm256_set1_epi8(0x5C);
    __m256i m_lbrace   = _mm256_set1_epi8(0x7B);
    __m256i m_rbrace   = _mm256_set1_epi8(0x7D);
    __m256i m_comma    = _mm256_set1_epi8(0x2C);
    __m256i m_lbracket = _mm256_set1_epi8(0x5B);
    __m256i m_rbracket = _mm256_set1_epi8(0x5D);

    // 4) Compute the 8‑bit movemasks
    uint64_t colonMask    = (uint64_t)_mm256_movemask_epi8(_mm256_cmpeq_epi8(v, m_colon));
    uint64_t quoteMask    = (uint64_t)_mm256_movemask_epi8(_mm256_cmpeq_epi8(v, m_quote));
    uint64_t escapeMask   = (uint64_t)_mm256_movemask_epi8(_mm256_cmpeq_epi8(v, m_escape));
    uint64_t lbraceMask   = (uint64_t)_mm256_movemask_epi8(_mm256_cmpeq_epi8(v, m_lbrace));
    uint64_t rbraceMask   = (uint64_t)_mm256_movemask_epi8(_mm256_cmpeq_epi8(v, m_rbrace));
    uint64_t commaMask    = (uint64_t)_mm256_movemask_epi8(_mm256_cmpeq_epi8(v, m_comma));
    uint64_t lbracketMask = (uint64_t)_mm256_movemask_epi8(_mm256_cmpeq_epi8(v, m_lbracket));
    uint64_t rbracketMask = (uint64_t)_mm256_movemask_epi8(_mm256_cmpeq_epi8(v, m_rbracket));

    // 5) Package into a Java long[] of length 8
    jlongArray out = env->NewLongArray(8);
    jlong tmp[8] = {
        (jlong)colonMask,
        (jlong)quoteMask,
        (jlong)escapeMask,
        (jlong)lbraceMask,
        (jlong)rbraceMask,
        (jlong)commaMask,
        (jlong)lbracketMask,
        (jlong)rbracketMask
    };
    env->SetLongArrayRegion(out, 0, 8, tmp);

    // 6) Clean up and return
    env->ReleaseByteArrayElements(inArr, in, JNI_ABORT);
    return out;
}

/*
 * Class:     JsonSimd
 * Method:    computeStrMask
 * Signature: (JJ)J
 */
JNIEXPORT jlong JNICALL
Java_JsonSimd_computeStrMask(JNIEnv* env, jobject /*self*/,
                             jlong quoteBits, jlong /*prevInside*/) {
    // 1) Build a 128-bit vector with quoteBits in the low lane
    __m128i v128 = _mm_set_epi64x(0ULL, (uint64_t)quoteBits);

    // 2) Carry-less multiply by 0xFF… to do a prefix XOR
    __m128i prod = _mm_clmulepi64_si128(v128, _mm_set1_epi8(0xFFu), 0);

    // 3) Extract low 64 bits
    uint64_t str_mask = (uint64_t)_mm_cvtsi128_si64(prod);

    // Return raw str_mask (Java will XOR with prevIterInsideQuote)
    return (jlong)str_mask;
}
