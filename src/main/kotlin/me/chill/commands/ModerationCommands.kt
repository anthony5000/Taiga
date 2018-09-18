package me.chill.commands

import me.chill.arguments.types.*
import me.chill.database.operations.*
import me.chill.database.states.TargetChannel
import me.chill.database.states.TimeMultiplier
import me.chill.framework.CommandCategory
import me.chill.framework.commands
import me.chill.infraction.UserInfractionRecord
import me.chill.roles.assignRole
import me.chill.roles.getMutedRole
import me.chill.roles.hasRole
import me.chill.roles.removeRole
import me.chill.settings.*
import me.chill.utility.getDateTime
import me.chill.utility.int
import me.chill.utility.jda.*
import me.chill.utility.str
import net.dv8tion.jda.core.JDA
import net.dv8tion.jda.core.entities.Guild
import net.dv8tion.jda.core.entities.Member
import net.dv8tion.jda.core.entities.MessageChannel
import net.dv8tion.jda.core.entities.User
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.concurrent.timerTask

// todo: move embeds into another class to reduce pollution
@CommandCategory
fun moderationCommands() = commands("Moderation") {
	command("nuke") {
		expects(Integer(0, 99))
		execute {
			guild.deleteMessagesFromChannel(
				channel.id,
				channel.getMessageHistory(arguments[0]!!.int() + 1)
			)
		}
	}

	command("echo") {
		expects(ChannelId(), Sentence())
		execute {
			val messageChannel = guild.getTextChannelById(arguments[0]!!.str())
			val message = arguments[1]!!.str()
			if (message.contains(Regex("(<@\\d*>)|(<@&\\d*>)|(@everyone)|(@here)"))) {
				respond(
					failureEmbed(
						"Echo",
						"Cannot echo a message with a member/role mention",
						thumbnail = noWay
					)
				)
				return@execute
			}
			messageChannel.send(message)
		}
	}

	command("mute") {
		expects(UserId(), Integer(), Sentence())
		execute {
			muteUser(guild, channel, guild.getMemberById(arguments[0]!!.str()), arguments[2]!!.str(), arguments[1]!!.int())
		}
	}

	command("history") {
		expects(UserId(true))
		execute {
			val targetId = arguments[0]!!.str()
			respond(historyEmbed(guild, jda.findUser(targetId), jda, getHistory(guild.id, targetId)))
		}
	}

	command("strike") {
		expects(UserId(), Integer(0, 3), Sentence())
		execute {
			strikeUser(guild, arguments[0]!!.str(), channel, arguments[1]!!.int(), arguments[2]!!.str(), invoker)
		}
	}

	command("warn") {
		expects(UserId(), Sentence())
		execute {
			val targetId = arguments[0]!!.str()
			val strikeReason = arguments[1]!!.str()
			strikeUser(guild, targetId, channel, 0, strikeReason, invoker)
		}
	}

	command("ban") {
		expects(UserId(true), Sentence())
		execute {
			val target = jda.findUser(arguments[0]!!.str())
			val banReason = arguments[1]!!.str()
			guild.controller.ban(target, 1, banReason).complete()
			respond(
				successEmbed(
					"User Banned",
					"User: **${target.name}** has been banned for **$banReason**"
				)
			)
		}
	}

	command("banall") {
		expects(ArgumentList(UserId(true)), Sentence())
		execute {
			val banList = arguments[0]!!.str().split(",")
			val banReason = "Mass Ban: ${arguments[1]!!.str()}"
			Thread {
				banList.forEach { target ->
					guild.controller.ban(jda.findUser(target), 1, banReason).complete()
					Thread.sleep(300)
				}
			}.start()
			respond(
				successEmbed(
					"Mass Ban",
					"Banning users: ${banList.joinToString(", ")}",
					null
				)
			)
		}
	}

	command("unban") {
		expects(UserId(true))
		execute {
			val target = jda.findUser(arguments[0]!!.str())
			guild.controller.unban(target).complete()
			addStrike(guild.id, target.id, 0, "Unbanned on **${getDateTime()}**", invoker.user.id)
			respond(
				successEmbed(
					"User Unbanned",
					"User: **${target.name}** has been unbanned",
					null
				)
			)
		}
	}

	command("wiperecord") {
		expects(UserId(true))
		execute {
			val targetId = arguments[0]!!.str()
			wipeRecord(guild.id, targetId)
			respond(
				successEmbed(
					"Records Wiped",
					"User: **${jda.findUser(targetId).name}**'s history has been wiped!",
					clap
				)
			)
		}
	}

	command("gag") {
		expects(UserId())
		execute {
			muteUser(
				guild,
				channel,
				guild.getMemberById(arguments[0]!!.str()),
				"You have been gagged whilst moderators handle an ongoing conflict. Please be patient.",
				5,
				TimeMultiplier.M)
		}
	}

	command("clearstrike") {
		expects(UserId(true), StrikeId())
		execute {
			val target = arguments[0]!!.str()
			val toRemove = arguments[1]!!.int()

			if (!userHasStrike(guild.id, target, toRemove)) {
				respond(
					failureEmbed(
						"Clear Strike Fail",
						"User: **${jda.findUser(target).name}** does not have strike **$toRemove**"
					)
				)
				return@execute
			}

			removeStrike(guild.id, target, toRemove)
			respond(
				successEmbed(
					"Strike Removed",
					"Strike: **$toRemove** has been cleared from ${jda.findUser(target).name}"
				)
			)
		}
	}
}

private fun strikeUser(guild: Guild, targetId: String, channel: MessageChannel,
					   strikeWeight: Int, strikeReason: String, invoker: Member) {
	val guildId = guild.id
	val target = guild.getMemberById(targetId)
	val loggingChannel = guild.getTextChannelById(getChannel(TargetChannel.Logging, guildId))

	addStrike(guildId, targetId, strikeWeight, strikeReason, invoker.user.id)
	val strikeCount = getStrikeCount(guildId, targetId)
	guild.getMemberById(targetId)
		.sendPrivateMessage(
			userStrikeNotificationEmbed(guild.name, strikeReason, strikeWeight, strikeCount)
		)

	loggingChannel.send(strikeSuccessEmbed(strikeWeight, target, strikeReason))

	when {
		strikeCount == 1 -> muteUser(guild, channel, target, "Muted due to infraction", timeMultiplier = TimeMultiplier.H)
		strikeCount == 2 -> muteUser(guild, channel, target, "Muted due to infraction", timeMultiplier = TimeMultiplier.D)
		strikeCount >= 3 -> guild.controller.ban(target, 1, strikeReason).complete()
	}
}

private fun muteUser(guild: Guild, channel: MessageChannel,
					 target: Member, reason: String,
					 duration: Int = 1, timeMultiplier: TimeMultiplier? = null) {
	val loggingChannel = guild.getTextChannelById(getChannel(TargetChannel.Logging, guild.id))
	val targetId = target.user.id

	if (!guild.hasRole("muted")) {
		channel.send(
			failureEmbed(
				"Mute Failed",
				"Unable to apply mute to user as the **muted** role does not exist, run `${getPrefix(guild.id)}setup`"
			)
		)
		return
	}

	val guildTimeMultiplier = timeMultiplier ?: getTimeMultiplier(guild.id)

	val mutedRole = guild.getMutedRole()
	assignRole(guild, mutedRole!!.id, targetId)
	target.sendPrivateMessage(userMuteNotificationEmbed(guild.name, duration, reason, guildTimeMultiplier))

	val muteDuration =
		if (timeMultiplier != null) duration * timeMultiplier.multiplier
		else duration * guildTimeMultiplier.multiplier

	Timer().schedule(
		timerTask {
			removeRole(guild, mutedRole.id, targetId)
			target.sendPrivateMessage(
				simpleEmbed(
					"Unmuted",
					"You have been unmuted in **${guild.name}**",
					null,
					cyan
				)
			)

			loggingChannel.send(
				simpleEmbed(
					"User Unmuted",
					"User: ${printMember(target)} has been unmuted",
					null,
					orange
				)
			)
		},
		muteDuration
	)

	loggingChannel.send(muteSuccessEmbed(target, duration, reason, guildTimeMultiplier))
}

private fun historyEmbed(guild: Guild, user: User, jda: JDA, userInfractionRecord: UserInfractionRecord) =
	embed {
		val dateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy")
		val joinDate = if (guild.getMember(user) == null) {
			"User is not on the server"
		} else {
			guild.getMember(user).joinDate.format(dateTimeFormatter)
		}

		title = "${user.name}'s History"
		color = cyan
		thumbnail = user.avatarUrl

		field {
			title = "Summary"
			description =
				"${user.name}#${user.discriminator} has **${userInfractionRecord.getStrikes().size}** infraction(s)\n" +
				"Current Strike Count: **${getStrikeCount(guild.id, user.id)}/3**\n" +
				"Join Date: **$joinDate**\n" +
				"Creation Date: **${user.creationTime.format(dateTimeFormatter)}**"
		}

		field {}

		if (userInfractionRecord.getStrikes().isEmpty()) {
			field {
				title = "This user has no infractions"
				description = "Squeaky clean!"
			}
		} else {
			userInfractionRecord.getStrikes().forEach { userStrike ->
				val isExpired = if (DateTime.now() > userStrike.expiryDate) "expired" else "not expired"

				field {
					title = "ID :: ${userStrike.strikeId} :: Weight :: ${userStrike.strikeWeight}"
					description =
						"This infraction is **$isExpired**\n" +
						"Issued by **${jda.findUser(userStrike.actingModeratorId).name}** " +
						"on **${DateTimeFormat.forPattern("dd-MM-yyyy").print(userStrike.strikeDate)}**\n" +
						"__**Reason:**__\n${userStrike.strikeReason}"
				}
			}
		}
	}

private fun strikeSuccessEmbed(strikeWeight: Int, target: Member, strikeReason: String) =
	embed {
		title = "User Striked"
		color = orange
		description = "${printMember(target)} has been striked"

		field {
			title = "Reason"
			description = strikeReason
			inline = false
		}

		field {
			title = "Weight"
			description = strikeWeight.toString()
			inline = false
		}
	}

private fun userStrikeNotificationEmbed(guildName: String, strikeReason: String,
										strikeWeight: Int, strikeCount: Int) =
	embed {
		title = "Strike"
		description = "You have been striked in **$guildName**"
		color = red

		field {
			title = "Reason"
			description = strikeReason
		}

		field {
			title = "Weight"
			description = strikeWeight.toString()
		}

		field {
			title = "Infraction Status"
			description = "Your strike count is at **$strikeCount/3**"
		}
	}

private fun userMuteNotificationEmbed(guildName: String, duration: Int, reason: String, guildTimeMultiplier: TimeMultiplier) =
	embed {
		title = "Mute"
		description = "You have been muted in **$guildName**"
		color = red

		field {
			title = "Reason"
			description = reason
		}

		field {
			title = "Duration"
			description = "$duration ${guildTimeMultiplier.fullTerm}(s)"
		}
	}

private fun muteSuccessEmbed(member: Member, duration: Int, reason: String, guildTimeMultiplier: TimeMultiplier) =
	embed {
		title = "User Muted"
		description = "User: ${printMember(member)} has been muted for **$duration** ${guildTimeMultiplier.fullTerm}(s)"
		color = green
		field {
			title = "Reason"
			description = reason
		}
	}