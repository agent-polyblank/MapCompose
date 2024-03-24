package ovh.plrapps.mapcompose.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileInputStream

/**
 * Test the [TileCollector.collectTiles] engine. The following assertions are tested:
 * * The Bitmap flow should pick a [Bitmap] from the pool if possible
 * * If [TileSpec]s are send to the input channel, corresponding [Tile]s are received from the
 * output channel (from the [TileCollector.collectTiles] point of view).
 * * The [Bitmap] of the [Tile]s produced should be consistent with the output of the flow
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P])
class TileCollectorTest {

    private val tileSize = 256

    companion object {
        private var assetsDir: File? = null

        init {
            try {
                val mapviewDirURL = TileCollectorTest::class.java.classLoader!!.getResource("tiles")
                assetsDir = File(mapviewDirURL.toURI())
            } catch (e: Exception) {
                println("No tiles directory found.")
            }

        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun fullTest() = runTest {
        assertNotNull(assetsDir)
        val imageFile = File(assetsDir, "10.jpg")
        assertTrue(imageFile.exists())

        /* Setup the channels */
        val visibleTileLocationsChannel = Channel<TileSpec>(capacity = Channel.RENDEZVOUS)
        val tilesOutput = Channel<Tile>(capacity = Channel.RENDEZVOUS)

        val pool = BitmapPool(Dispatchers.Default.limitedParallelism(1))

        val tileStreamProvider = TileStreamProvider { _, _, _ -> FileInputStream(imageFile) }

        val bitmapReference = try {
            val inputStream = FileInputStream(imageFile)
            val bitmapLoadingOptions = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeStream(inputStream, null, bitmapLoadingOptions)
        } catch (e: Exception) {
            fail()
            error("Could not decode image")
        }

        fun CoroutineScope.consumeTiles(tileChannel: ReceiveChannel<Tile>) = launch {
            for (tile in tileChannel) {
                println("received tile ${tile.zoom}-${tile.row}-${tile.col}")
                val bitmap = tile.bitmap
                assertTrue(tile.bitmap?.sameAs(bitmapReference) ?: false)

                /* Add bitmap to the pool only if they are from level 0 */
                if (tile.zoom == 0) {
                    if (bitmap != null) {
                        pool.put(bitmap)
                    }
                }
            }
        }

        val layers = listOf(
            Layer("default", tileStreamProvider)
        )

        /* Start consuming tiles */
        val tileConsumeJob = launch {
            consumeTiles(tilesOutput)
        }

        /* Start collecting tiles */
        val tileCollector = TileCollector(1, BitmapConfiguration(Bitmap.Config.RGB_565, 2), tileSize)
        val tileCollectorJob = launch {
            tileCollector.collectTiles(visibleTileLocationsChannel, tilesOutput, layers, pool)
        }

        launch {
            val locations1 = listOf(
                    TileSpec(0, 0, 0),
                    TileSpec(0, 1, 1),
                    TileSpec(0, 2, 1)
            )
            for (spec in locations1) {
                visibleTileLocationsChannel.send(spec)
            }

            val locations2 = listOf(
                    TileSpec(1, 0, 0),
                    TileSpec(1, 1, 1),
                    TileSpec(1, 2, 1)
            )
            /* Bitmaps inside the pool should be used */
            for (spec in locations2) {
                visibleTileLocationsChannel.send(spec)
            }

            tileCollectorJob.cancel()
            tileConsumeJob.cancel()
        }
        Unit
    }
}