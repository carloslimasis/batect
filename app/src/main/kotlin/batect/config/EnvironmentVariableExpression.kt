/*
   Copyright 2017-2018 Charles Korn.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

package batect.config

import com.fasterxml.jackson.annotation.JsonCreator

sealed class EnvironmentVariableExpression {
    abstract fun evaluate(hostEnvironmentVariables: Map<String, String>): String

    companion object {
        private val patterns = listOf(
            Regex("\\$\\{(.+):-(.*)}") to { match: MatchResult -> ReferenceValue(match.groupValues[1], match.groupValues[2]) },
            Regex("\\$\\{([^:]+)}") to { match: MatchResult -> ReferenceValue(match.groupValues[1]) },
            Regex("\\$([^{](.*[^}])?)") to { match: MatchResult -> ReferenceValue(match.groupValues[1]) },
            Regex("[^$].*") to { match: MatchResult ->
                if (match.value.startsWith("\\$")) {
                    LiteralValue(match.value.drop(1))
                } else {
                    LiteralValue(match.value)
                }
            }
        )

        @JvmStatic
        @JsonCreator
        fun parse(source: String): EnvironmentVariableExpression {
            patterns.forEach { (pattern, generator) ->
                val result = pattern.matchEntire(source)

                if (result != null) {
                    return generator(result)
                }
            }

            throw IllegalArgumentException("Invalid environment variable expression '$source'")
        }

        @JvmStatic
        @JsonCreator
        fun parse(source: Int): EnvironmentVariableExpression = LiteralValue(source.toString())

        @JvmStatic
        @JsonCreator
        fun parse(source: Long): EnvironmentVariableExpression = LiteralValue(source.toString())

        // We can't reliably convert booleans or doubles back to strings that are exactly as the user specified them, so just give up.
        // These methods exist to give the user a nicer error message in this case.
        @JvmStatic
        @JsonCreator
        fun parse(@Suppress("UNUSED_PARAMETER") source: Boolean): EnvironmentVariableExpression = throw UnsupportedOperationException("Environment variable value is not a recognised type. (Try wrapping the value in double quotes.)")

        @JvmStatic
        @JsonCreator
        fun parse(@Suppress("UNUSED_PARAMETER") source: Double): EnvironmentVariableExpression = throw UnsupportedOperationException("Environment variable value is not a recognised type. (Try wrapping the value in double quotes.)")
    }
}

data class LiteralValue(val value: String) : EnvironmentVariableExpression() {
    override fun evaluate(hostEnvironmentVariables: Map<String, String>) = value
    override fun toString() = "${this::class.simpleName}(value: '$value')"
}

data class ReferenceValue(val referenceTo: String, val default: String? = null) : EnvironmentVariableExpression() {
    override fun evaluate(hostEnvironmentVariables: Map<String, String>): String {
        val hostValue = hostEnvironmentVariables.get(referenceTo)

        if (hostValue != null) {
            return hostValue
        } else if (default != null) {
            return default
        } else {
            throw EnvironmentVariableExpressionEvaluationException("The host environment variable '$referenceTo' is not set, and no default value has been provided.")
        }
    }

    override fun toString() = "${this::class.simpleName}(reference to: '$referenceTo', default: '$default')"
}

class EnvironmentVariableExpressionEvaluationException(message: String) : RuntimeException(message)