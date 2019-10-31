/*
 * Copyright 2019-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.facebook.buck.multitenant.fs

import com.facebook.buck.io.pathformat.PathFormatter
import com.facebook.buck.multitenant.cache.AppendOnlyBidirectionalCache
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.databind.deser.std.FromStringDeserializer
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import java.nio.file.FileSystem
import java.nio.file.Path

/**
 * Cache between path [String] and [FsAgnosticPath] wrapper around this [String] value.
 * Soft references are used.
 * Softly-referenced objects will be garbage-collected in a <i>globally</i> least-recently-used manner,
 * in response to memory demand.
 */
private val PATH_CACHE: Cache<String, FsAgnosticPath> =
    CacheBuilder.newBuilder().softValues().build()

/**
 * Cache between [FsAgnosticPath] to unique [Int] value.
 */
private val PATH_TO_INDEX_CACHE = AppendOnlyBidirectionalCache<FsAgnosticPath>()

/**
 * Prefer this to [java.nio.file.Path] in the multitenant packages. Whereas a [java.nio.file.Path]
 * is associated with a [java.nio.file.FileSystem], [FsAgnosticPath] is basically just a glorified
 * wrapper around a [String] for type safety with `Path`-like methods.
 *
 * This path will always serialize itself using '/' as the path separator, even on Windows.
 *
 * Note this is not a `data class` because the `copy()` method would expose the private constructor.
 */
@JsonSerialize(using = ToStringSerializer::class)
@JsonDeserialize(using = FsAgnosticPathDeserializer::class)
class FsAgnosticPath private constructor(private val path: String) : Comparable<FsAgnosticPath> {
    companion object {
        /**
         * @param path must be a normalized, relative path.
         */
        fun of(path: String): FsAgnosticPath {
            return PATH_CACHE.getIfPresent(path) ?: run {
                verifyPath(path)
                createWithoutVerification(path)
            }
        }

        /**
         * @param path must be a normalized, relative path.
         */
        fun of(path: Path): FsAgnosticPath = of(PathFormatter.pathWithUnixSeparators(path))

        /** Caller is responsible for verifying that the string is well-formed. */
        private fun createWithoutVerification(verifiedPath: String): FsAgnosticPath {
            val internedPath = verifiedPath.intern()
            val fsAgnosticPath = FsAgnosticPath(internedPath)
            PATH_CACHE.put(internedPath, fsAgnosticPath)
            return fsAgnosticPath
        }

        /**
         * Returns [FsAgnosticPath] associated with the given [index]
         */
        fun fromIndex(index: Int): FsAgnosticPath = PATH_TO_INDEX_CACHE.getByIndex(index)

        /**
         * Returns index value associated with the given [FsAgnosticPath]
         */
        fun toIndex(fsAgnosticPath: FsAgnosticPath): Int = PATH_TO_INDEX_CACHE.get(fsAgnosticPath)
    }

    override fun compareTo(other: FsAgnosticPath): Int {
        return path.compareTo(other.path)
    }

    fun isEmpty(): Boolean {
        return path.isEmpty()
    }

    fun startsWith(prefixPath: FsAgnosticPath): Boolean {
        return if (path.startsWith(prefixPath.path)) {
            if (prefixPath.path.isEmpty() || prefixPath.path.length == path.length) {
                true
            } else {
                path[prefixPath.path.length] == '/'
            }
        } else {
            false
        }
    }

    /**
     * @return a path that is resolved against this path.
     */
    fun resolve(other: FsAgnosticPath): FsAgnosticPath {
        return when {
            isEmpty() -> other
            other.isEmpty() -> this
            else -> createWithoutVerification("$path/$other")
        }
    }

    /**
     * @return the last component of the path, which is either a file name or directory name,
     * including extension if it has one
     */
    fun name(): FsAgnosticPath {
        val lastIndex = path.lastIndexOf('/')
        return if (lastIndex == -1) this
        else createWithoutVerification(path.substring(lastIndex + 1))
    }

    /**
     * Similar to [Path.getParent], except it never returns `null`: it will return the empty path
     * if the path does not have a parent.
     */
    fun dirname(): FsAgnosticPath {
        val lastIndex = path.lastIndexOf('/')
        return createWithoutVerification(if (lastIndex == -1) "" else path.substring(0, lastIndex))
    }

    override fun equals(other: Any?): Boolean {
        return when {
            (this === other) -> true
            (other is FsAgnosticPath) -> path == other.path
            else -> false
        }
    }

    override fun hashCode(): Int {
        return path.hashCode()
    }

    override fun toString(): String {
        return path
    }

    /**
     * Converts a [FsAgnosticPath] string representation to a [Path] in the given [fileSystem].
     */
    @SuppressWarnings("SpreadOperator")
    fun toPath(fileSystem: FileSystem): Path {
        val pathStrings = toString().split("/")
        val first = pathStrings[0]
        return if (pathStrings.size > 1) {
            fileSystem.getPath(first, *pathStrings.subList(1, pathStrings.size).toTypedArray())
        } else {
            fileSystem.getPath(first)
        }
    }
}

private fun verifyPath(path: String) {
    if (path == "") {
        return
    }

    require(!path.startsWith('/')) { "'$path' must be relative but starts with '/'" }
    require(!path.endsWith('/')) { "'$path' cannot have a trailing slash" }

    for (component in path.split("/")) {
        require(component != "") { "'$path' contained an empty path component" }
        require(component != ".") { "'$path' contained illegal path component: '.'" }
        require(component != "..") { "'$path' contained illegal path component: '..'" }
    }
}

class FsAgnosticPathDeserializer :
    FromStringDeserializer<FsAgnosticPath>(FsAgnosticPath::class.java) {
    override fun _deserialize(value: String, ctxt: DeserializationContext): FsAgnosticPath {
        return FsAgnosticPath.of(value)
    }

    override fun _deserializeFromEmptyString(): FsAgnosticPath {
        return FsAgnosticPath.of("")
    }
}
