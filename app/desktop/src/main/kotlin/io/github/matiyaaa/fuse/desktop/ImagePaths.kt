package io.github.matiyaaa.fuse.desktop

import coil3.Uri
import coil3.map.Mapper
import coil3.request.Options
import coil3.toUri

/**
 * Art on a Windows drive ("C:/Users/u/Fuse/media/x.png"). Coil reads "C:" as a URI scheme unless the
 * path uses backslashes, so this hands it over that way.
 */
internal object WindowsImagePaths : Mapper<String, Uri> {
    override fun map(data: String, options: Options): Uri? {
        if (data.length < 3 || data[1] != ':' || !data[0].isLetter() || (data[2] != '/' && data[2] != '\\')) return null
        return data.replace('/', '\\').toUri(separator = "\\")
    }
}
