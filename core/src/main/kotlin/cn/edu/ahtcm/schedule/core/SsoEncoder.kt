package cn.edu.ahtcm.schedule.core

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** Wire format used by the school's strEnc(data, "1", "2", "3").
 * Its key permutation differs from standard DES PC-1. Remap the key bits
 * before using the platform cipher; this is compatibility, not password storage.
 */
internal object SsoEncoder {
    private val pc1 = intArrayOf(57,49,41,33,25,17,9,1,58,50,42,34,26,18,10,2,59,51,43,35,27,19,11,3,60,52,44,36,63,55,47,39,31,23,15,7,62,54,46,38,30,22,14,6,61,53,45,37,29,21,13,5,28,20,12,4)
    fun encode(value: String): String {
        val ciphers = ('1'..'3').map { char ->
            val raw = byteArrayOf(0, char.code.toByte(), 0, 0, 0, 0, 0, 0)
            val key = ByteArray(8)
            for (n in 0 until 56) {
                val source = (7 - n % 8) * 8 + n / 8
                val bit = (raw[source / 8].toInt() ushr (7 - source % 8)) and 1
                val dest = pc1[n] - 1
                key[dest / 8] = (key[dest / 8].toInt() or (bit shl (7 - dest % 8))).toByte()
            }
            Cipher.getInstance("DES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES")) }
        }
        return buildString {
            for (offset in value.indices step 4) {
                var block = ByteArray(8)
                for (j in 0 until minOf(4, value.length - offset)) {
                    val code = value[offset + j].code
                    block[j * 2] = (code ushr 8).toByte()
                    block[j * 2 + 1] = code.toByte()
                }
                ciphers.forEach { block = it.doFinal(block) }
                block.forEach { append("%02X".format(it.toInt() and 255)) }
            }
        }
    }
}
