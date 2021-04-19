package viper.gobra.reporting
import viper.silicon.{reporting => sil}
import viper.gobra.reporting._
import viper.gobra.frontend.info.base.Type._


trait GobraInterpreter extends sil.ModelInterpreter[GobraModelEntry,Type]
trait GobraDomainInterpreter[T] extends sil.DomainInterpreter[GobraModelEntry,T]

case class DefaultGobraInterpreter() extends GobraInterpreter{
	override def interpret(entry:sil.ExtractedModelEntry,info:Type): GobraModelEntry = DummyEntry()
}

/**
  * interprets all Extracted values to Gobra values using its sub interpreters for each individual type
  *
  * @param c
  */
case class MasterInterpreter(c:sil.Converter) extends GobraInterpreter{
	val optionInterpreter: GobraDomainInterpreter[OptionT] = OptionInterpreter(c)
	val productInterpreter: GobraDomainInterpreter[StructT] = ProductInterpreter(c)
	val boxInterpreter:GobraDomainInterpreter[Type] = BoxInterpreter(c)
	def interpret(entry:sil.ExtractedModelEntry,info:Type): GobraModelEntry ={
		entry match{
			case sil.LitIntEntry(v) => LitIntEntry(v)
			case sil.LitBoolEntry(b) => LitBoolEntry(b)
			case sil.LitPermEntry(p) => LitPermEntry(p)
			case v:sil.VarEntry => interpret(c.extractVal(v),info)//TODO:make shure this does not pingpong
			case d:sil.DomainValueEntry => info match {//TODO: More interpreters
												case t:OptionT => optionInterpreter.interpret(d,t)
												case t:StructT =>  productInterpreter.interpret(d,t)
												case t:ArrayT =>boxInterpreter.interpret(d,t)
												case t:SliceT => FaultEntry("TODO: Silce")
												case DeclaredT(d,c) => val name = d.left.name
																		val actual = interpret(entry,c.symbType(d.right)) match {
																			case l:LitEntry => l
																			case _ => FaultEntry("not a lit entry")
																		}
																		LitDeclaredEntry(name,actual)
												case _ => DummyEntry()
											}
			case sil.ExtendedDomainValueEntry(o,i) => interpret(o,info)
			case s:sil.SeqEntry => FaultEntry("Sequence sould not be unboxed...")
			case _ => FaultEntry("illegal call of interpret")
		}
	}
}

case class OptionInterpreter(c:sil.Converter) extends GobraDomainInterpreter[OptionT]{
	val nonFuncName :String = "optIsNone"//TODO: find out where they are generated
	val getFuncName : String = "optGet"
	def interpret(entry:sil.DomainValueEntry,info:OptionT): GobraModelEntry = {
		val doms = c.domains.filter(x=>x.valueName==entry.domain)
		if(doms.length==1){
			val functions:Seq[sil.ExtractedFunction] =doms.head.functions
			val noneFunc : sil.ExtractedFunction = functions.find(_.fname==nonFuncName) match {
																							case Some(value) => value; 
																							case None => return FaultEntry(s"${entry}: could not relsove ($nonFuncName) not found")}
			val getFunc : sil.ExtractedFunction= functions.find(_.fname==getFuncName) match {
																						case Some(value) => value; 
																						case None => return FaultEntry(s"${entry}: could not relsove ($getFuncName) not found")
																					}
			val isNone : Boolean = noneFunc.apply(Seq(entry)) match {
				case Right(sil.LitBoolEntry(b)) => b
				case _ => return FaultEntry(s"${entry}: could not relsove ($nonFuncName)")
			}
			if(isNone){
				LitOptionEntry(None)
			}else{
				val actualval:sil.ExtractedModelEntry = getFunc.apply(Seq(entry)) match {
					case Right(v) => v
					case _ => return FaultEntry(s"${entry}: could not relsove ($getFuncName)")
				}
				val gobraval = MasterInterpreter(c).interpret(actualval,info.elem) match {
					case e:LitEntry => e
					case x => return FaultEntry(s"internal error: ${x}")
				}
				LitOptionEntry(Some(gobraval))
			}
		}else{
			FaultEntry(s"could not relsove domain: ${entry.domain}")
		}
	}
}
case class ProductInterpreter(c:sil.Converter) extends GobraDomainInterpreter[StructT]{
	//NOTE: they are just strigs in the implementation with no way of extracting them
	def getterFunc(i:Int,n:Int) = s"get${i}of$n" //TODO: find out where they are generated 
	def interpret(entry:sil.DomainValueEntry,info:StructT) :GobraModelEntry ={
		val doms = c.domains.find(_.valueName==entry.domain)
		if(doms.isDefined){
			//printf(s"${doms.get}")
			val functions:Seq[sil.ExtractedFunction] =doms.get.functions
			val fields = info.fields
			val numFields = fields.size
			val getterNames = Seq.range(0,numFields).map(getterFunc(_,numFields))
			val getterfuncs = getterNames.map(x=>functions.find(_.fname==x)).collect({case Some(v)=> v})
			val fieldToVals = (fields.keys.toSeq.zip(getterfuncs).map(
												x=>(x._1,x._2.apply(Seq(entry)) match {case Right(v)=> v; 
																				case _ => return FaultEntry(s"${entry}: could not relsove (${x._1})")
																			})
												)).toMap
			try{
			val values = fields.map(x=>(x._1,MasterInterpreter(c).interpret(fieldToVals.apply(x._1),x._2) match{
																										case l:LitEntry=> l;
																										case _ => return FaultEntry("internal error struct")
																									}
										)) 
			LitStructEntry(info,values)
			}catch{
				case t:Throwable => printf(s"$t");return FaultEntry(s"${entry.domain} wrong domain for type: ${info.toString.replace("\n",";")}")
			}
			
		}else{
			FaultEntry(s"could not relsove domain: ${entry.domain}")
		}
	}
}
//experimental (somehow the thing does not work...)
case class BoxInterpreter(c:sil.Converter) extends GobraDomainInterpreter[Type]{
	def unboxFunc(domain:String) = s"unbox_$domain"
	def boxFunc(domain:String) = s"box_$domain"
	def interpret(entry:sil.DomainValueEntry,info:Type):GobraModelEntry={
		val unbox = c.non_domain_functions.find(_.fname==unboxFunc(entry.domain))
		val box = c.non_domain_functions.find(_.fname==boxFunc(entry.domain))
		//printf(s"$box \n $unbox")
		if(unbox.isDefined){
			//printf(s"$entry")
			val unboxed = Right(unbox.get.default) match{ //unboxing has some strange behaviour snaps and such
					case Right(v:sil.VarEntry) => c.extractVal(v)
					case Right(x) => x
					case _ => FaultEntry(s"wrong application of function $unbox")
			} 
			unboxed match{
				case x:sil.SeqEntry => info match{
													case a:ArrayT => LitArrayEntry(a,
																		x.values.map(y=>MasterInterpreter(c).interpret(y,a.elem)  match {
																			case l:LitEntry => l
																			case _ => FaultEntry("not a lit entry")
																		}))
													}
				case _=> FaultEntry(s"$unboxed not a box entry...")
			}
			//MasterInterpreter(c).interpret(unboxed,UnknownType)
		}else{
			FaultEntry(s"${unboxFunc(entry.domain)} not found")
		}
	}
}
case class IndexedInterpreter(c:sil.Converter) extends GobraDomainInterpreter[ArrayT] {
	def lenName(d:String) = s"${d}len" //TODO : replace with Name
	def index(d:String) = s"${d}loc"
	def interpret(entry:sil.DomainValueEntry,info:ArrayT) :GobraModelEntry ={
		val doms = c.domains.find(_.valueName==entry.domain)
		if(doms.isDefined){
			//printf(s"${doms.get}")
			val functions:Seq[sil.ExtractedFunction] =doms.get.functions
			val lengthFunc = functions.find(_.fname==lenName(doms.get.name))
			val length = lengthFunc match{
				case Some(value) => value.apply(Seq(entry)) match{
					case Right(i:sil.LitIntEntry) => i.value
					case _=> return FaultEntry("length ill defined")
				}
				case None =>  return FaultEntry("length not found")
			}
			//require(length==info.length) this cannot be enforced because we do not always know the array length ahead of time
			val offsetFunc = functions.find(_.fname==index(doms.get.name))
			if(offsetFunc.isDefined){
				val indexFunc = offsetFunc.get
				val values = 0.until(length.toInt-1).map(i=>{
				val x = indexFunc.apply(Seq(entry,sil.LitIntEntry(i))) match{
					case Right(x) => MasterInterpreter(c).interpret(x,info.elem) match {
						case x:LitEntry => x
						case _ => FaultEntry("not a literal")
					}
					case _=> return FaultEntry("could not resolve")
				}
				x
				})
				LitArrayEntry(info,values)
			}else{
				FaultEntry(s"offset not found")
			}
			
		}else{
			FaultEntry(s"could not relsove domain: ${entry.domain}")
		}
	}
}
case class SliceInterpreter(c:sil.Converter) extends GobraDomainInterpreter[SliceT]{
	/* function sarray(s : Slice[T]) : ShArray[T]
    *   function soffset(s : Slice[T]) : Int
    *   function slen(s : Slice[T]) : Int
    *   function scap(s : Slice[T]) : Int
	* */
	//TODO delegate to Names (src/main/scala/viper/gobra/translator/Names.scala)
	val sarray = "sarray"
	val soffset = "soffset"
	val slen = "slen"
	def interpret(entry:sil.DomainValueEntry,info:SliceT):GobraModelEntry={
		val doms = c.domains.find(_.valueName==entry.domain)
		if(doms.isDefined){
			val functions = doms.get.functions
			val funcSarray = functions.find(_.fname==sarray)
			val funcOffset = functions.find(_.fname==soffset)
			val funclength = functions.find(_.fname==slen)
			if(funclength.isDefined && funcOffset.isDefined && funcSarray.isDefined){
				val length = funclength.get.apply(Seq(entry)) match{
					case Right(i:sil.LitIntEntry) => i.value
					case _ => return FaultEntry("length not defined")
				}
				val offset = funcOffset.get.apply(Seq(entry)) match{
					case Right(i:sil.LitIntEntry) => i.value
					case _ => return FaultEntry("offset not defined")
				}
				val (array,arraytyp) = funcSarray.get.apply(Seq(entry)) match{
					case Right(x) => x match {
						case v:sil.VarEntry => c.extractVal(v) match {
														case s:sil.SeqEntry => (s,ArrayT(s.values.size,info.elem))
														case _ => return FaultEntry("extracted Value not a sequence")
													}
						case s:sil.SeqEntry => (s,ArrayT(s.values.size,info.elem))
						case d:sil.DomainValueEntry => (d,ArrayT(0,info.elem)) 
						case _ => (sil.OtherEntry("internal","error"),UnknownType)
					}
					case _ => return FaultEntry(s"$sarray false application")
				}
				val original = MasterInterpreter(c).interpret(array,arraytyp) match {
					case x:LitArrayEntry => x
					case _=> return FaultEntry("not an array")
				}
				LitSliceEntry(info,original.values.drop(offset.toInt).take(length.toInt))
			}else{
				FaultEntry(s"functions ($sarray ,$soffset, $slen) not found")
			}

		}else{
			FaultEntry(s"${entry.domain} not found")
		}
	}
}

case class PointerInterpreter(c:sil.Converter) extends sil.ModelInterpreter[GobraModelEntry,PointerT]{
	def interpret(entry:sil.ExtractedModelEntry,info:PointerT): GobraModelEntry ={
		DummyEntry()
	}
}
