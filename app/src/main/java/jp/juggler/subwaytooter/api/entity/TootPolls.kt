package jp.juggler.subwaytooter.api.entity

import android.content.Context
import android.text.Spannable
import android.text.SpannableString
import jp.juggler.subwaytooter.R
import jp.juggler.subwaytooter.api.TootParser
import jp.juggler.subwaytooter.util.*
import jp.juggler.util.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.regex.Pattern

enum class TootPollsType {
	Mastodon, // Mastodon 2.8
	Misskey, // Misskey
	FriendsNico, // friends.nico API
}

class TootPollsChoice(
	val text : String,
	val decoded_text : Spannable,
	var isVoted : Boolean = false, // misskey
	var votes : Int? = 0, // misskey
	var checked : Boolean = false // Mastodon
)

class TootPolls private constructor(
	parser : TootParser,
	status : TootStatus,
	list_attachment : ArrayList<TootAttachmentLike>?,
	src : JSONObject,
	val pollType : TootPollsType
) {
	
	// one of enquete,enquete_result
	val type : String?
	
	val question : String? // HTML text
	
	val decoded_question : Spannable // 表示用にデコードしてしまうのでNonNullになる
	
	// array of text with emoji
	val items : ArrayList<TootPollsChoice>?
	
	// 結果の数値 // null or array of number
	var ratios : MutableList<Float>? = null
	
	// 結果の数値のテキスト // null or array of string
	private var ratios_text : MutableList<String>? = null
	
	var myVoted : Int? = null
	
	// 以下はJSONには存在しないが内部で使う
	val time_start : Long
	val status_id : EntityId
	
	// Mastodon poll API
	var expired_at = Long.MAX_VALUE
	var expired = false
	var multiple = false
	var votes_count : Int? = null
	var maxVotesCount : Int? = null
	var pollId : EntityId? = null
	
	init {
		
		this.time_start = status.time_created_at
		this.status_id = status.id
		
		when(pollType) {
			TootPollsType.Misskey -> {
				
				this.items = parseChoiceListMisskey(
					
					src.optJSONArray("choices")
				)
				
				val votesList = ArrayList<Int>()
				var votesMax = 1
				items?.forEachIndexed { index, choice ->
					if(choice.isVoted) this.myVoted = index
					val votes = choice.votes ?: 0
					votesList.add(votes)
					if(votes > votesMax) votesMax = votes
				}
				
				if(votesList.isNotEmpty()) {
					this.ratios =
						votesList.map { (it.toFloat() / votesMax.toFloat()) }.toMutableList()
					this.ratios_text =
						votesList.map { parser.context.getString(R.string.vote_count_text, it) }
							.toMutableList()
				} else {
					this.ratios = null
					this.ratios_text = null
				}
				
				this.type = TYPE_ENQUETE
				
				this.question = status.content
				this.decoded_question = DecodeOptions(
					parser.context,
					parser.linkHelper,
					short = true,
					decodeEmoji = true,
					attachmentList = list_attachment,
					linkTag = status,
					emojiMapCustom = status.custom_emojis,
					emojiMapProfile = status.profile_emojis
				).decodeHTML(this.question ?: "?")
				
			}
			
			TootPollsType.Mastodon -> {
				this.type = "enquete"
				
				this.question = status.content
				this.decoded_question = DecodeOptions(
					parser.context,
					parser.linkHelper,
					short = true,
					decodeEmoji = true,
					attachmentList = list_attachment,
					linkTag = status,
					emojiMapCustom = status.custom_emojis,
					emojiMapProfile = status.profile_emojis
				).decodeHTML(this.question ?: "?")
				
				this.items = parseChoiceListMastodon(
					parser.context,
					status,
					src.optJSONArray("options")?.toObjectList()
				)
				
				this.pollId = EntityId.mayNull(src.parseString("id"))
				this.expired_at =
					TootStatus.parseTime(src.parseString("expires_at")).notZero() ?: Long.MAX_VALUE
				this.expired = src.optBoolean("expired", false)
				this.multiple = src.optBoolean("multiple", false)
				this.votes_count = src.parseInt("votes_count")
				this.myVoted = if(src.optBoolean("voted", false)) 1 else null
				
				when {
					this.items == null -> maxVotesCount = null
					
					this.multiple -> {
						var max : Int? = null
						for(item in items) {
							val v = item.votes
							if(v != null && (max == null || v > max)) max = v
							
						}
						maxVotesCount = max
					}
					
					else -> {
						var sum : Int? = null
						for(item in items) {
							val v = item.votes
							if(v != null) sum = (sum ?: 0) + v
						}
						maxVotesCount = sum
					}
				}
				
			}
			
			TootPollsType.FriendsNico -> {
				this.type = src.parseString("type")
				
				this.question = src.parseString("question")
				this.decoded_question = DecodeOptions(
					parser.context,
					parser.linkHelper,
					short = true,
					decodeEmoji = true,
					attachmentList = list_attachment,
					linkTag = status,
					emojiMapCustom = status.custom_emojis,
					emojiMapProfile = status.profile_emojis
				).decodeHTML(this.question ?: "?")
				
				this.items = parseChoiceListFriendsNico(
					parser.context,
					status,
					src.parseStringArrayList("items")
				)
				
				this.ratios = src.parseFloatArrayList("ratios")
				this.ratios_text = src.parseStringArrayList("ratios_text")
			}
		}
		
	}
	
	companion object {
		internal val log = LogCategory("TootPolls")
		
		const val ENQUETE_EXPIRE = 30000L
		
		const val TYPE_ENQUETE = "enquete"
		
		@Suppress("unused")
		const val TYPE_ENQUETE_RESULT = "enquete_result"
		
		@Suppress("HasPlatformType")
		private val reWhitespace = Pattern.compile("[\\s\\t\\x0d\\x0a]+")
		
		fun parse(
			parser : TootParser,
			status : TootStatus,
			list_attachment : ArrayList<TootAttachmentLike>?,
			src : JSONObject?,
			pollType : TootPollsType
		) : TootPolls? {
			src ?: return null
			return try {
				TootPolls(
					parser,
					status,
					list_attachment,
					src,
					pollType
				)
			} catch(ex : Throwable) {
				log.trace(ex)
				null
			}
		}
		
		private fun parseChoiceListMastodon(
			context : Context,
			status : TootStatus,
			objectArray : ArrayList<JSONObject>?
		) : ArrayList<TootPollsChoice>? {
			if(objectArray != null) {
				val size = objectArray.size
				val items = ArrayList<TootPollsChoice>(size)
				val options = DecodeOptions(
					context,
					emojiMapCustom = status.custom_emojis,
					emojiMapProfile = status.profile_emojis,
					decodeEmoji = true
				)
				for(o in objectArray) {
					val text = reWhitespace
						.matcher((o.parseString("title") ?: "?").sanitizeBDI())
						.replaceAll(" ")
					val decoded_text = options.decodeEmoji(text)
					
					items.add(
						TootPollsChoice(
							text,
							decoded_text,
							votes = o.parseInt("votes_count") // may null
						)
					)
				}
				if(items.isNotEmpty()) return items
			}
			return null
		}
		
		private fun parseChoiceListFriendsNico(
			context : Context,
			status : TootStatus,
			stringArray : ArrayList<String>?
		) : ArrayList<TootPollsChoice>? {
			if(stringArray != null) {
				val size = stringArray.size
				val items = ArrayList<TootPollsChoice>(size)
				val options = DecodeOptions(
					context,
					emojiMapCustom = status.custom_emojis,
					emojiMapProfile = status.profile_emojis,
					decodeEmoji = true
				)
				for(i in 0 until size) {
					val text = reWhitespace
						.matcher(stringArray[i].sanitizeBDI())
						.replaceAll(" ")
					val decoded_text = options.decodeHTML(text)
					
					items.add(
						TootPollsChoice(
							text,
							decoded_text
						)
					)
				}
				if(items.isNotEmpty()) return items
			}
			return null
		}
		
		private fun parseChoiceListMisskey(
			choices : JSONArray?
		) : ArrayList<TootPollsChoice>? {
			if(choices != null) {
				val items = ArrayList<TootPollsChoice>()
				for(i in 0 until choices.length()) {
					val src = choices.optJSONObject(i)
					
					val text = reWhitespace
						.matcher(src.parseString("text")?.sanitizeBDI() ?: "")
						.replaceAll(" ")
					val decoded_text = SpannableString(text) // misskey ではマークダウン不可で絵文字もない
					
					val dst = TootPollsChoice(
						text = text,
						decoded_text = decoded_text,
						// 配列インデクスと同じだった id = EntityId.mayNull(src.parseLong("id")),
						votes = src.parseInt("votes") ?: 0,
						isVoted = src.optBoolean("isVoted")
					)
					items.add(dst)
				}
				
				if(items.isNotEmpty()) return items
			}
			return null
		}
	}
	
	// misskey用
	fun increaseVote(context : Context, argChoice : Int?, isMyVoted : Boolean) : Boolean {
		argChoice ?: return false
		
		synchronized(this) {
			try {
				// 既に投票済み状態なら何もしない
				if(myVoted != null) return false
				
				val item = this.items?.get(argChoice) ?: return false
				item.votes = (item.votes ?: 0) + 1
				if(isMyVoted) item.isVoted = true
				
				// update ratios
				val votesList = ArrayList<Int>()
				var votesMax = 1
				items.forEachIndexed { index, choice ->
					if(choice.isVoted) this.myVoted = index
					val votes = choice.votes ?: 0
					votesList.add(votes)
					if(votes > votesMax) votesMax = votes
				}
				
				if(votesList.isNotEmpty()) {
					
					this.ratios = votesList.asSequence()
						.map { (it.toFloat() / votesMax.toFloat()) }
						.toMutableList()
					
					this.ratios_text = votesList.asSequence()
						.map { context.getString(R.string.vote_count_text, it) }
						.toMutableList()
					
				} else {
					this.ratios = null
					this.ratios_text = null
				}
				
				return true
				
			} catch(ex : Throwable) {
				log.e(ex, "increaseVote failed")
				return false
			}
			
		}
	}
	
}
