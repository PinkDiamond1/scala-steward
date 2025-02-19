/*
 * Copyright 2018-2022 Scala Steward contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.scalasteward.core.update

import cats.syntax.all._
import cats.{Monad, TraverseFilter}
import org.scalasteward.core.data._
import org.scalasteward.core.repoconfig.RepoConfig
import org.scalasteward.core.update.FilterAlg._
import org.scalasteward.core.util.Nel
import org.typelevel.log4cats.Logger

final class FilterAlg[F[_]](implicit
    logger: Logger[F],
    F: Monad[F]
) {
  def localFilterMany[G[_]: TraverseFilter](
      config: RepoConfig,
      updates: G[Update.Single]
  ): F[G[Update.Single]] =
    updates.traverseFilter(update => logIfRejected(localFilter(update, config)))

  private def logIfRejected(result: FilterResult): F[Option[Update.Single]] =
    result match {
      case Right(update) => F.pure(update.some)
      case Left(reason) =>
        logger.info(s"Ignore ${reason.update.show} (reason: ${reason.show})").as(None)
    }
}

object FilterAlg {
  type FilterResult = Either[RejectionReason, Update.Single]

  sealed trait RejectionReason {
    def update: Update.Single
    def show: String =
      this match {
        case IgnoredByConfig(_)         => "ignored by config"
        case VersionPinnedByConfig(_)   => "version is pinned by config"
        case NotAllowedByConfig(_)      => "not allowed by config"
        case NoSuitableNextVersion(_)   => "no suitable next version"
        case VersionOrderingConflict(_) => "version ordering conflict"
      }
  }

  final case class IgnoredByConfig(update: Update.Single) extends RejectionReason
  final case class VersionPinnedByConfig(update: Update.Single) extends RejectionReason
  final case class NotAllowedByConfig(update: Update.Single) extends RejectionReason
  final case class NoSuitableNextVersion(update: Update.Single) extends RejectionReason
  final case class VersionOrderingConflict(update: Update.Single) extends RejectionReason

  def localFilter(update: Update.Single, repoConfig: RepoConfig): FilterResult =
    repoConfig.updates.keep(update).flatMap(globalFilter)

  private def globalFilter(update: Update.Single): FilterResult =
    selectSuitableNextVersion(update).flatMap(checkVersionOrdering)

  def isDependencyConfigurationIgnored(dependency: Dependency): Boolean =
    dependency.configurations.fold("")(_.toLowerCase) match {
      case "phantom-js-jetty"    => true
      case "scalafmt"            => true
      case "scripted-sbt"        => true
      case "scripted-sbt-launch" => true
      case "tut"                 => true
      case _                     => false
    }

  private def selectSuitableNextVersion(update: Update.Single): FilterResult = {
    val newerVersions = update.newerVersions.toList
    val maybeNext = update.currentVersion.selectNext(newerVersions)
    maybeNext match {
      case Some(next) => Right(update.copy(newerVersions = Nel.of(next)))
      case None       => Left(NoSuitableNextVersion(update))
    }
  }

  private def checkVersionOrdering(update: Update.Single): FilterResult = {
    val current = coursier.core.Version(update.currentVersion.value)
    val next = coursier.core.Version(update.nextVersion.value)
    if (current > next) Left(VersionOrderingConflict(update)) else Right(update)
  }
}
