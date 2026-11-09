package dev.leconnn.ioskeyboard.text

import java.math.BigDecimal
import java.math.MathContext

/**
 * iOS "Show Math Results": when the text before the cursor ends with an arithmetic expression
 * followed by "=", returns the result to offer as a suggestion ("12*4=" -> "48").
 */
object MathCompletion {
    private val trailingExpression = Regex("""([0-9.,+\-*/×÷^()%\s]+)=$""")

    fun resultFor(textBeforeCursor: CharSequence): String? {
        val expr = trailingExpression.find(textBeforeCursor)?.groupValues?.get(1)?.trim() ?: return null
        if (expr.isEmpty() || expr.none { it.isDigit() }) return null
        val parser = Parser(expr.replace(",", ""))
        val value = runCatching { parser.parse() }.getOrNull() ?: return null
        // A lone number ("5=") or a signed number ("-5=") is not a calculation.
        if (!parser.sawBinaryOperator || value.isNaN() || value.isInfinite()) return null
        return format(value)
    }

    /** Up to 10 significant digits, no trailing zeros, no exponent. */
    fun format(v: Double): String {
        val bd = BigDecimal(v).round(MathContext(10)).stripTrailingZeros()
        return if (bd.signum() == 0) "0" else bd.toPlainString()
    }

    /**
     * Recursive descent: expr = term (('+'|'-') term)*; term = power (('*'|'/'|'×'|'÷') power)*;
     * power = unary ('^' power)?; unary = '-' unary | postfix; postfix = primary '%'*;
     * primary = number | '(' expr ')'. "%" divides by 100, as on the iOS calculator.
     */
    private class Parser(s: String) {
        private val src = s.filterNot { it.isWhitespace() }
        private var i = 0
        var sawBinaryOperator = false

        fun parse(): Double {
            val v = expr()
            require(i == src.length) { "trailing input" }
            return v
        }

        private fun peek() = src.getOrNull(i)

        private fun expr(): Double {
            var v = term()
            while (peek() == '+' || peek() == '-') {
                val op = src[i++]
                val r = term()
                sawBinaryOperator = true
                v = if (op == '+') v + r else v - r
            }
            return v
        }

        private fun term(): Double {
            var v = power()
            while (peek() in listOf('*', '/', '×', '÷')) {
                val op = src[i++]
                val r = power()
                sawBinaryOperator = true
                v = if (op == '*' || op == '×') v * r else {
                    require(r != 0.0) { "division by zero" }
                    v / r
                }
            }
            return v
        }

        private fun power(): Double {
            val base = unary()
            if (peek() != '^') return base
            i++
            val exp = power()
            sawBinaryOperator = true
            return Math.pow(base, exp)
        }

        private fun unary(): Double = if (peek() == '-') { i++; -unary() } else postfix()

        private fun postfix(): Double {
            var v = primary()
            while (peek() == '%') { i++; v /= 100 }
            return v
        }

        private fun primary(): Double {
            if (peek() == '(') {
                i++
                val v = expr()
                require(peek() == ')') { "missing )" }
                i++
                return v
            }
            val start = i
            while (peek()?.let { it.isDigit() || it == '.' } == true) i++
            require(i > start) { "number expected" }
            return src.substring(start, i).toDouble()
        }
    }
}
