/**
 * Copyright (C) 2018 DANS - Data Archiving and Networked Services (info@dans.knaw.nl)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nl.knaw.dans.lib.bagstore

import java.net.URI
import java.nio.file.{ Path, Paths }
import java.nio.file.attribute.{ PosixFilePermission, PosixFilePermissions }
import java.util.UUID

import better.files.File
import nl.knaw.dans.lib.error._
import nl.knaw.dans.lib.logging.DebugEnhancedLogging

import scala.collection.JavaConverters._
import scala.language.postfixOps
import scala.util.{ Failure, Success, Try }

/**
 * Interface to a bag store. Use [[BagStore.apply the constructor on the companion object]] to create an instance to work with.
 *
 * = Bag store operations =
 *
 * The methods of this trait implement some of the operations as defined on the Definitions page of the bag store project:
 *
 *  - `ADD`: [[BagStore.add]]
 *  - `GET`: the various overloads of get
 *  - `ENUM` (for bags): [[BagStore.enum]]
 *
 * The other operations are defined in [[Item]] and its subclasses. To retrieve an item object, use one of overloads of `get` passing it the
 * appropriate item-id. Use the item object to access the properties of corresponding bag or file in the bag store, or to perform one of the
 * operations on it:
 *
 *  - `ENUM` (for files in a bag): [[BagItem.enum]]
 *  - `DEACTIVATE`: [[BagItem.deactivate]]
 *  - `REACTIVATE`: [[BagItem.reactivate]]
 *  - `ERASE`: [[RegularFileItem.erase]]
 *
 * = Utility functions =
 *
 * Additionally, some utility function are provided:
 *
 *  - [[verify]] - to verify that a bag store is consistent
 *  - [[prune]] - to minimize a bag before adding it to this bag store
 *  - [[complete]] - to complete a minimized bag
 *
 *
 * = Example code =
 *
 * {{{
 *   val bagStore = BagStore(File("/data/bagstore"), File("/data/staging"))
 *   val bagToAdd = File("/home/jdoe/bag1")
 *
 *   for {
 *     // Replace files already in bag store with fetch-includes
 *     _ <- bagStore.prune(bagToAdd)
 *     // Double check that bagToAdd is virtually-valid (will also be done by add)
 *     virtValid <- bagStore.isVirtuallyValid(bagToAdd)
 *     // Add it to the bag store
 *     id <- bagStore.add(File("/home/jdoe/bag1"))
 *     // Check that you can retrieve it
 *     item <- bagStore.get(id)
 *     // Where is the bag stored?
 *     location <- item.getLocation
 *     // Print the location
 *     _ = println(location)
 *     // Deactivate the bag
 *     _ <- item.deactivate()
 *   } yield ()
 *
 *
 *
 *
 * }}}
 *
 *
 */
trait BagStore {
  // TODO: create separate library easy-bag-store-streaming to implement getting streams of packaged files from bag stores.
  // TODO: implement autoPrune parameter in add?
  // TODO: implement autoComplete parameter in get?
  // TODO: split up BagStoreImpl into helpers so that this file can be smaller
  /**
   * Adds a bag to the bag store. A UUID to use as bag-id may be provided. If it is not, it is
   * generated by the function. The bag must be virtually-valid in the context of this bag store,
   * otherwise the function fails. Note that it may be possible to [[prune]] the bag before adding it.
   *
   * Currently, only unserialized bags are supported.
   *
   * By default, a copy of `bag` will be staged to `stagingDir`. However, the caller can skip this
   * step and have `bag` be moved into the bag store from its current location. In that case, the current
   * location must be on the same partition as the bag store itself, so that an atomic move operation is
   * possible.
   *
   * A hidden file cannot be added to the bag store, as it would result in a bag that starts off inactive.
   *
   * @param bag     the bag to add
   * @param optUuid the UUID to use as bag-id
   * @param move    move (instead of copy) the bag into the bag store*
   * @return the added [[BagItem]]
   */
  def add(bag: File, optUuid: Option[UUID] = None, move: Boolean = false): Try[BagItem]

  /**
   * Returns an [[Item]]. The type of the returned object is a subclass of [[Item]]. If you know what
   * type of item you expect, you may use one the overloaded methods.
   *
   * @param itemId the item-id of the item to get
   * @return the item, if found
   */
  def get(itemId: ItemId): Try[Item]

  /**
   * Returns a [[FileItem]].
   *
   * @param fileId the file-id of the file to get
   * @return the file, if found
   */
  def get(fileId: FileId): Try[FileItem]

  /**
   * Returns a [[BagItem]].
   *
   * @param bagId the bag-id of the bag to get
   * @return the bag, if found
   */
  def get(bagId: BagId): Try[BagItem]

  /**
   * Returns a [[DirectoryItem]].
   *
   * @param directoryId the file-id of the directory to get
   * @return the directory, if found
   */
  def get(directoryId: DirectoryId): Try[DirectoryItem]

  /**
   * Returns a [[RegularFileItem]]
   *
   * @param regularFileId the file-id of the regular file to get
   * @return the regular file, if found
   */
  def get(regularFileId: RegularFileId): Try[RegularFileItem]

  /**
   * Returns a [[RegularFileItem]]
   *
   * @param localFileUri the local-file-uri of the regular file to get
   * @return the regular file, if found
   */
  def get(localFileUri: URI): Try[RegularFileItem]

  /**
   * Returns a `Stream` that enumerates all the bags in the bag store. The order in which the bags appear in the stream undetermined.
   * However, all bags are guaranteed to appear. The ony exceptions to this are that an I/O error occurs or the bag store turns out to be
   * corrupt. In thoses cases the stream returns a `Failure` when the error occurs and will not return any more items after that.
   *
   * By default only active bags are included, but this behavior can be overridden using the input parameters.
   *
   * @param includeActive   whether to include active bags
   * @param includeInactive whether to include inactive bags
   * @return a stream of bags
   */
  def enum(includeActive: Boolean = true, includeInactive: Boolean = false): Try[Stream[Try[BagItem]]]

  /**
   * Verifies that that bag store is uncorrupted. The checks include, in the following order:
   *
   *  1. The slashing pattern is used consistently.
   *  2. There are no empty container directories.
   *  3. All the bags are virtually-valid.
   *
   * Obviously, this may take a long time to complete, depending on the size of the store and the performance of
   * the storage it is located on. By default the whole store will be checked, but it is possible to cut the operation
   * short on the first violation that is encountered.
   *
   * @param failOnFirstViolation whether to fail on the first violation encountered, or keep checking the rest of the bag store
   * @return success or failure
   */
  def verify(failOnFirstViolation: Boolean = true): Try[Unit]

  /**
   * Determines whether `bag` is virtually-valid with respect to this bag store. The bag may or may not be
   * stored in the bag store currently.
   *
   * @param bag the bag to validate
   * @return `Right(())` if virtually-valid, or `Left(msg)` in which `msg` details the reasons why the bag is not virtually-valid
   */
  def isVirtuallyValid(bag: File): Try[Either[String, Unit]]

  /**
   * Removes from `bag` the payload files which are found in one of the reference bags and adds a fetch reference to
   * the file in that reference bag. The result is a smaller bag, which is still virtually valid relative to this
   * bag store.
   *
   * @param bag      the bag directory to process
   * @param refBagId the bag-ids in which to search
   */
  def prune(bag: File, refBagId: BagId*): Try[Unit]

  /**
   * Downloads the fetch items in `fetch.txt` and removes `fetch.txt` and any entries for it in tagmanifests, so as to make `bag`
   * valid. Optionally, resolution can be limited to only local-file-uris or only remote URIs. In that case the bag may still not
   * be valid after the operation. However, it will be virtually-valid if it was so before.
   *
   * @param bag                  the bag to process
   * @param resolveLocalFileUris whether to resolve local-file-uris
   * @param resolveRemoteUris    whether to resolve remote URIs
   */
  def complete(bag: File, resolveLocalFileUris: Boolean = true, resolveRemoteUris: Boolean = true): Try[Unit]
}

object BagStore {
  val fetchTxtFilename: String = "fetch.txt"
  val defaultSlashPattern: SlashPattern = SlashPattern(2, 30)
  val defaultBagDirPermissions: Set[PosixFilePermission] = PosixFilePermissions.fromString("r-xr-xr-x").asScala.toSet
  val defaultBagFilePermissions: Set[PosixFilePermission] = PosixFilePermissions.fromString("r--r--r--").asScala.toSet
  val defaultContainerDirPermissions: Set[PosixFilePermission] = PosixFilePermissions.fromString("rwxr-xr-x").asScala.toSet
  val defaultLocalFileBaseUri: URI = new URI("http://localhost/")

  /**
   * Creates an object that represents an existing bag store. The bag store may be empty. If it already has content the onus is on the caller
   * to ensure that the other parameters are consistent with it.
   *
   * The staging directory `stagingDir` must be on the same partition as the bag store, so that an atomic
   * move operation is possible.
   *
   * @param baseDir                 the base directory of the bag store
   * @param stagingDir              the directory to use for staging bags
   * @param slashPattern            the slash pattern to use when creating containing directories for the bag
   * @param containerDirPermissions the permissions to set on container directories and their parents inside the bag store
   * @param bagDirPermissions       the permissions to set on directories added to the bag store
   * @param bagFilePermissions      the permissions to set on regular files added to the bag store
   *
   */
  def apply(baseDir: File, stagingDir: File,
            slashPattern: SlashPattern = defaultSlashPattern,
            bagDirPermissions: Set[PosixFilePermission] = defaultBagDirPermissions,
            bagFilePermissions: Set[PosixFilePermission] = defaultBagFilePermissions,
            containerDirPermissions: Set[PosixFilePermission] = defaultContainerDirPermissions,
            localFileBaseUri: URI = defaultLocalFileBaseUri): BagStoreImpl = {
    new BagStoreImpl(baseDir, stagingDir, slashPattern, bagDirPermissions, bagFilePermissions, containerDirPermissions, localFileBaseUri)
  }
}

private[bagstore] class BagStoreImpl(val baseDir: File,
                                     val stagingDir: File,
                                     val slashPattern: SlashPattern = SlashPattern(2, 30),
                                     val bagDirPermissions: Set[PosixFilePermission] = PosixFilePermissions.fromString("r-xr-xr-x").asScala.toSet,
                                     val bagFilePermissions: Set[PosixFilePermission] = PosixFilePermissions.fromString("r--r--r--").asScala.toSet,
                                     val containerDirPermissions: Set[PosixFilePermission] = PosixFilePermissions.fromString("rwxr-xr-x").asScala.toSet,
                                     val localFileBaseUri: URI = new URI("http://localhost/")) extends BagStore with DebugEnhancedLogging {
  require(baseDir isDirectory, "baseDir must be an existing directory")
  require(baseDir isReadable, "baseDir must be readable")
  require(baseDir isWriteable, "baseDir must be writeable")
  require(stagingDir isDirectory, "stagingDir must be an existing directory")
  require(stagingDir isReadable, "stagingDir must be readable")
  require(stagingDir isWriteable, "stagingDir must be writeable")

  override def add(bag: File, optUuid: Option[UUID] = None, move: Boolean = false): Try[BagItem] = {
    trace(bag, optUuid, move)

    def checkArgs = Try {
      require(!bag.isHidden, "Input bag must be a non-hidden file")
    }

    def getUuid = Try {
      optUuid.getOrElse {
        val uuid = UUID.randomUUID()
        debug(s"Generated UUID: $uuid")
        uuid
      }
    }

    def stageBagIfRequired = Try {
      if (move) {
        debug("Bag to be moved. No staging necessary.")
        bag
      }
      else {
        val tempDir = File.newTemporaryDirectory("bag-to-add-", parent = Some(stagingDir))
        debug(s"Created temp dir to stage bag to at: $tempDir")
        bag copyToDirectory tempDir
      }
    }

    def createContainer(uuid: UUID) = Try {
      val container = baseDir / slashPattern.applyTo(uuid).toString
      container.createDirectories()
      debug(s"Created container at $container")
      getAncestors(container).withFilter(baseDir.isParentOf).foreach(_.setPermissions(containerDirPermissions))
      container
    }

    def moveBagInto(container: File)(bag: File) = Try {
      debug("Setting permissions inside the bag.")
      bag.walk().filter(_ != bag).foreach {
        f =>
          if (f.isDirectory) f setPermissions bagDirPermissions
          else f setPermissions bagFilePermissions
      }
      val movedBag = bag moveToDirectory container
      debug("Setting permissions on the bag itself.")
      movedBag setPermissions bagDirPermissions
    }.recover {
      case _ =>
        logger.error("Move into container failed. Deleting container and empty ancestors")
        container.delete(swallowIOExceptions = true)
        getAncestors(container).withFilter(d => baseDir.isParentOf(d) && baseDir != d).foreach {
          d => if (d.list.isEmpty) d.delete()
        }
    }

    for {
      _ <- checkArgs
      bagToMove <- stageBagIfRequired
      validity <- isVirtuallyValid(bagToMove)
      _ <- validity match {
        case Right(()) => Success(())
        case Left(problems) =>
          if (!move) bagToMove.parent.delete(swallowIOExceptions = true)
          Failure(NonVirtuallyValidBagException(problems))
      }
      uuid <- getUuid
      container <- createContainer(uuid)
      _ <- if (move) moveBagInto(container)(bagToMove)
           // Make parent dir self-destruct after operation.
           else bagToMove.parent.toTemporary {
             _ => moveBagInto(container)(bagToMove)
           }
    } yield BagItem(this, uuid)
  }

  override def get(itemId: ItemId): Try[Item] = {
    trace(itemId)
    itemId match {
      case bagId: BagId => get(bagId)
      case fileId: FileId => get(fileId)
      // To suppress compiler warning, but this should be unreachable code.
      case id => Failure(new IllegalArgumentException(s"Unknown type of id: $id"))
    }
  }

  override def get(bagId: BagId): Try[BagItem] = {
    trace(bagId)
    val bagItem = BagItem(this, bagId.uuid)
    // Check that the bag exists, but return the bagItem
    bagItem.getLocation.map(_ => bagItem)
  }

  override def get(fileId: FileId): Try[FileItem] = {
    trace(fileId)
    get(RegularFileId(fileId.uuid, fileId.path))
      .recoverWith {
        case _: NoSuchItemException => get(DirectoryId(fileId.uuid, fileId.path))
      }
  }

  override def get(directoryId: DirectoryId): Try[DirectoryItem] = {
    trace(directoryId)

    def checkExists(bagDir: File): Try[Unit] = Try {
      import scala.language.postfixOps
      val pattern = ("^[0-9a-f]\\s+" + directoryId.path + "/[^/]+$").r
      if (bagDir.list.filter(f => f.name.startsWith("manifest-") || f.name.startsWith("tagmanifest-"))
        // FIXME: take character encoding into account
        .exists(pattern findFirstIn _.contentAsString isDefined)) Success(())
      else Failure(NoSuchItemException(s"directory: $directoryId"))
    }

    for {
      bagItem <- get(BagId(directoryId.uuid))
      bagDir <- bagItem.getLocation
      _ <- checkExists(bagDir)
    } yield DirectoryItem(BagItem(this, directoryId.uuid), directoryId.path)
  } // TODO: get(dir) and get(regularfile) basically have the same structure: can we factor that out?

  override def get(regularFileId: RegularFileId): Try[RegularFileItem] = {
    trace(regularFileId)

    def checkExists(bagDir: File): Try[Unit] = Try {
      import scala.language.postfixOps
      val pattern = ("^[0-9a-f]\\s+" + regularFileId.path + "$").r
      if (bagDir.list.filter(f => f.name.startsWith("manifest-") || f.name.startsWith("tagmanifest-"))
        // FIXME: take character encoding into account
        .exists(pattern findFirstIn _.contentAsString isDefined)) Success(())
      else Failure(NoSuchItemException(s"regular file: $regularFileId"))
    }

    for {
      bagItem <- get(BagId(regularFileId.uuid))
      bagDir <- bagItem.getLocation
      _ <- checkExists(bagDir)
    } yield RegularFileItem(BagItem(this, regularFileId.uuid), regularFileId.path)
  }

  def get(localFileUri: URI): Try[RegularFileItem] = {
    def reportDifferentComponent(comp: String) = s"localFileUri must have the same $comp as local-file-base-uri: $localFileBaseUri"

    Try {
      require(localFileUri.getScheme == localFileBaseUri.getScheme, reportDifferentComponent("scheme"))
      require(localFileUri.getAuthority == localFileBaseUri.getAuthority, reportDifferentComponent("authority"))
      require(localFileUri.getHost == localFileBaseUri.getHost, reportDifferentComponent("host"))
      require(localFileUri.getPort == localFileBaseUri.getPort, reportDifferentComponent("port"))
      val path = Paths.get(localFileUri.getPath)
      val basePath = Paths.get(localFileBaseUri.getPath)
      require(path startsWith basePath, s"localFileUri must start with the same path as local-file-base-uri: $localFileBaseUri")

      basePath.relativize(path)
    }.flatMap {
      idPath =>
        if (idPath.getNameCount < 2) throw new IllegalArgumentException("uri has too few components to be regular file item uri")
        else Try { UUID.fromString(idPath.asScala.head.toString) }
          .map {
            uuid => RegularFileItem(BagItem(this, uuid), idPath.subpath(1, idPath.getNameCount))
          }
    }
  }

  override def enum(includeActive: Boolean = true, includeInactive: Boolean = false): Try[Stream[Try[BagItem]]] = {
    trace(includeActive, includeInactive)
    // TODO: Implement enum

    ???
  }

  /**
   * Verifies this bag store. The following invariants are checked (in this order):
   *
   * 1. The slash-pattern is maintained throughout the bag store.
   * 2. Every container only has one file in it.
   * 3. All bags are virtually-valid.
   *
   * If any of these invariants is violated the bag store is declared corrupt. By default the function will
   * stop at the first violation, but this behavior can be overridden.
   *
   * Note that, depending on the size of the bag store, this function may take a long time to complete.
   *
   * @param failOnFirstViolation whether to stop checking after encountering the first violation
   * @return
   */
  override def verify(failOnFirstViolation: Boolean = true): Try[Unit] = {
    trace(failOnFirstViolation)
    // TODO: Implement verify

    ???
  }

  override def isVirtuallyValid(bag: File): Try[Either[String, Unit]] = {
    trace(bag)

    def getRealToProjected(bag: File, pathsToFetch: Seq[Path]): Try[Map[File, File]] = {
      pathsToFetch.map(p => getFileDataLocation(bag, p).map { real => (real, bag / p.toString) }).collectResults.map(_.toMap)
    }

    def symLinkCompleteBag(realToProjected: Map[File, File]) = Try {
      realToProjected.map {
        case (real, projected) =>
          debug(s"Link from $projected to $real")
          if (projected isRegularFile) throw new IllegalArgumentException(s"Fetch item tries to overwrite existing file at $projected")
          if (projected isSymbolicLink) throw new IllegalArgumentException(s"Fetch item tries to overwrite another fetch item at $projected")
          projected.parent createDirectories()
          projected symbolicLinkTo real
      }
    }

    def verifyValid(i: BagInspector, b: File) = for {
      inspector <- createBagInspector(b)
      result <- inspector.verifyValid
    } yield result

    if (bag / BagStore.fetchTxtFilename exists) {
      for {
        tempDir <- Try { File.newTemporaryDirectory("symlink-bag-", Some(stagingDir)) }
        workBag <- symLinkCopy(bag, tempDir / bag.name)
        inspector <- createBagInspector(workBag)
        pathsToFetch <- inspector.getFetchItems.map(_.values.map(_.path))
        realToProjected <- getRealToProjected(workBag, pathsToFetch.toSeq)
        _ <- symLinkCompleteBag(realToProjected)
        /*
         * The BagIt specs https://tools.ietf.org/html/draft-kunze-bagit-14#section-3 is a bit vague about the
         * status of a bag that fulfills the requirements for a complete, and even a valid bag, except that it has
         * a fetch.txt. The question is whether having a fetch.txt makes the bag incomplete, or only having a
         * fetch.txt that does not list all the files (present in a manifest) currently missing from the bag.
         * My own (JvM) reading is, that the presence of the fetch.txt makes it incomplete by definition, and that
         * not listing the missing files in the fetch.txt makes it "beyond" incomplete - "uncompleteable", if you will.
         * (It would be one of the ways a bag can be invalid.)
         *
         * The bagit-java library, however, regards a bag containing a fetch.txt, but which has been fully resolved,
         * as valid, so we do not have do anything complicated here to check the virtual validity. If at some point we
         * switch to a bagit library that takes the other point of view. The following steps should be added:
         *
         * 1. Check that the tagmanifests check out
         * 2. Remove tagmanifests and fetch.txt
         * 3. Check what remains for validity.
         *
         * Also note, that we rely on the java-bagit library following symlinks.
         */
        result <- verifyValid(inspector, workBag)
        _ <- Try { tempDir.delete(swallowIOExceptions = true) }
        _ = if (tempDir exists) logger.warn(s"Symlink copy in $tempDir could not be (completely) removed!")
      } yield result
    }
    else {
      for {
        inspector <- createBagInspector(bag)
        result <- verifyValid(inspector, bag)
      } yield result
    }
  }

  /**
   * Resolves the relative `path` to a regular file item to the location where its file data is actually stored.
   *
   * @param bag  the bag in which the regular file is located, possibly only virtually
   * @param path the bag-relative path of the regular file
   * @return the real location
   */
  private[bagstore] def getFileDataLocation(bag: File, path: Path): Try[File] = {
    trace(bag, path)
    val location = bag / path.toString
    if (location isRegularFile) Try { location }
    else if (location exists) Failure(new IllegalArgumentException(s"$path points to an existing object, but not a regular file"))
    else for {
      inspector <- createBagInspector(bag)
      fetchItems <- inspector.getFetchItems
      _ = debug(s"fetchItems = $fetchItems")
      fetchItem <- Try { fetchItems.getOrElse(path, throw NoSuchItemException(s"$path is neither and existing file nor a fetch reference")) }
      fileItem <- get(fetchItem.uri)
      fileDataLocation <- fileItem.getFileDataLocation
    } yield fileDataLocation
  }

  private def createBagInspector(bag: File) = Try {
    BagInspector(bag)
  }.recoverWith {
    case t: Throwable => Failure(BagReaderException(bag, t))
  }

  override def prune(bag: File, refBagId: BagId*): Try[Unit] = {
    trace(bag, refBagId)
    // TODO: Implement prune
    ???
  }

  override def complete(bag: File, resolveLocalFileUris: Boolean, resolveRemoteUris: Boolean): Try[Unit] = {
    trace(bag, resolveLocalFileUris, resolveRemoteUris)
    // TODO: Implement complete
    ???
  }
}
