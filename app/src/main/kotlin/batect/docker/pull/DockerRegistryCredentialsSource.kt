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

package batect.docker.pull

import batect.docker.DockerRegistryCredentialsException
import batect.os.ExecutableDoesNotExistException
import batect.os.ProcessRunner
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonTreeParser
import java.nio.charset.Charset
import java.util.Base64

sealed class DockerRegistryCredentialsSource {
    abstract fun load(): DockerRegistryCredentials?
}

data class BasicCredentialsSource(val encodedCredentials: String, val serverAddress: String) : DockerRegistryCredentialsSource() {
    override fun load(): DockerRegistryCredentials {
        if (encodedCredentials == "") {
            throw DockerRegistryCredentialsException("Credentials for '$serverAddress' are empty.")
        }

        try {
            val decoded = Base64.getDecoder().decode(encodedCredentials).toString(Charset.defaultCharset())

            val parts = decoded.split(":", limit = 2)

            if (parts.size != 2) {
                throw DockerRegistryCredentialsException("Decoded credentials for '$serverAddress' are not valid.")
            }

            if (parts[0] == "<token>") {
                return TokenDockerRegistryCredentials(parts[1])
            } else {
                return PasswordDockerRegistryCredentials(parts[0], parts[1], serverAddress)
            }
        } catch (_: IllegalArgumentException) {
            throw DockerRegistryCredentialsException("Could not decode credentials for '$serverAddress'.")
        }
    }
}

data class HelperBasedCredentialsSource(val helperName: String, val serverAddress: String, private val processRunner: ProcessRunner) : DockerRegistryCredentialsSource() {
    override fun load(): DockerRegistryCredentials? {
        try {
            val command = listOf(helperName, "get")
            val result = processRunner.runAndCaptureOutput(command, serverAddress)
            val output = result.output.trim()

            if (result.exitCode != 0) {
                if (output == "credentials not found in native keychain") {
                    return null
                }

                throw DockerRegistryCredentialsException("Could not load credentials for '$serverAddress' because the credential helper executable '$helperName' exited with code ${result.exitCode} and output: $output")
            }

            val parsed = parseCredentials(output)
            val username = parsed.getStringMember("Username")
            val secret = parsed.getStringMember("Secret")
            val serverUrl = parsed.getStringMember("ServerURL")

            if (username == "<token>") {
                return TokenDockerRegistryCredentials(secret)
            } else {
                return PasswordDockerRegistryCredentials(username, secret, serverUrl)
            }
        } catch (e: ExecutableDoesNotExistException) {
            throw DockerRegistryCredentialsException("Could not load credentials for '$serverAddress' because the credential helper executable '$helperName' does not exist.", e)
        }
    }

    private fun parseCredentials(credentials: String): JsonObject {
        try {
            return JsonTreeParser(credentials).readFully().jsonObject
        } catch (e: Throwable) {
            throw DockerRegistryCredentialsException("The credentials returned for '$serverAddress' by the credential helper executable '$helperName' are invalid: ${e.message}", e)
        }
    }

    private fun JsonObject.getStringMember(key: String): String {
        if (this.containsKey(key)) {
            return this[key].primitive.content
        }

        throw DockerRegistryCredentialsException("The credentials returned for '$serverAddress' by the credential helper executable '$helperName' are invalid: there is no '$key' field.")
    }
}
