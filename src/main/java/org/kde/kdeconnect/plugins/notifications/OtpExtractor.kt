/*
 * SPDX-FileCopyrightText: 2026 Kusal Saraf <kusalsaraf5@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.notifications

/**
 * Finds one-time codes (OTPs) in notification text.
 */
object OtpExtractor {
    private val KEYWORD = Regex(
        """\b(otp|codes?|verification|verify|passcode|password|pin|one[- ]?time)\b""",
        RegexOption.IGNORE_CASE,
    )

    // 4-8 digits, or two groups of 3 digits joined by a hyphen. A candidate must not touch a letter or
    // digit, nor be joined to other digits by a separator or a space (phone numbers, dates, times, amounts).
    private val CANDIDATE = Regex("""(?<![\p{L}\d])(?<!\d[-/.,: ])(\d{3}-\d{3}|\d{4,8})(?![\p{L}\d])(?![-/.,: ]\d)""")

    // Text right before a number that marks it as an amount, an account or card number, or a date
    private val NOT_A_CODE_BEFORE = Regex(
        "(" +
            """\brs\.?|\binr|\busd|\beur|[₹$€£]""" + "|" +
            """\b(ending(\s+(in|with))?|ends\s+with|card|a/c|acct|account)(\s+no\.?)?""" + "|" +
            """\b(jan(uary)?|feb(ruary)?|mar(ch)?|apr(il)?|may|june?|july?|aug(ust)?|sept?(ember)?|oct(ober)?|nov(ember)?|dec(ember)?)\.?""" +
            """)[\s:#*-]*$""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Returns the digits of the one-time code in [text], or null if there is none.
     *
     * The text must contain a keyword such as "OTP" or "code". When there are several
     * candidate numbers, the one closest to a keyword wins.
     */
    fun find(text: String): String? {
        val keywords = KEYWORD.findAll(text).map { it.range }.toList()
        if (keywords.isEmpty()) {
            return null
        }
        return CANDIDATE.findAll(text)
            .filterNot { NOT_A_CODE_BEFORE.containsMatchIn(text.substring(0, it.range.first)) }
            .minByOrNull { candidate -> keywords.minOf { distance(candidate.range, it) } }
            ?.value
            ?.replace("-", "")
    }

    /**
     * Like [find], for a notification's [title] and [text]. The text is searched on its own first, so a
     * number in the title (such as the sender's phone number) can't beat the code in the message.
     */
    fun find(title: String?, text: String?): String? =
        text?.let { find(it) } ?: find(listOfNotNull(title, text).joinToString("\n"))

    private fun distance(a: IntRange, b: IntRange): Int = when {
        a.last < b.first -> b.first - a.last
        b.last < a.first -> a.first - b.last
        else -> 0
    }
}
