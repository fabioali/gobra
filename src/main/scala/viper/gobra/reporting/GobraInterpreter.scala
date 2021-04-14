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
				case t:Throwable => printf(s"$t");return FaultEntry(s"${entry.domain} wrong domain for type: $info")
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