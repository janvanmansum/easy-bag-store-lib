package nl.knaw.dans.lib.bagstore

import java.nio.file.Path

import better.files._

import scala.util.{ Success, Try }

/**
 * A File (RegularFile or Directory) stored in a BagStore.
 *
 * @param bagItem the parent BagItem
 * @param path the relative path in the BagItem
 */
class FileItem(bagItem: BagItem, path: Path) extends Item {

  override def getId: ItemId = {
    val BagId(uuid) = bagItem.getId
    FileId(uuid, path)
  }

  override def getLocation: Try[File] = bagItem.getLocation.map(_/path.toString)

}
