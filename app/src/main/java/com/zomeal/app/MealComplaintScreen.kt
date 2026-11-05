package com.zomeal.app

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.util.Locale

@Composable
internal fun MealFeedbackHeader(title:String,subtitle:String,enabled:Boolean=true,onBack:()->Unit){
 Row(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
  Surface(shape=RoundedCornerShape(14.dp),color=Color.White,border=BorderStroke(1.dp,Color(0xFFDCE8E0))){IconButton(enabled=enabled,onClick=onBack){Icon(Icons.Filled.ArrowBack,"Back",tint=Color(0xFF006B38))}}
  Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){Text(title,fontSize=22.sp,fontWeight=FontWeight.Bold,color=Color(0xFF10231B));Text(subtitle,fontSize=12.sp,color=Color(0xFF66716B))}
 }
}

@Composable
internal fun MealEvidenceSummary(meal:CustomerMealExperience) {
 val context=LocalContext.current
 val repo=remember{SupabaseCustomerRepository(context)}
 var image by remember(meal.mealId,meal.photoPath){mutableStateOf<android.graphics.Bitmap?>(null)}
 DisposableEffect(meal.photoPath){var active=true;if(meal.photoPath.isNotBlank())repo.approvedMedia(meal.photoPath){if(active)image=it};onDispose{active=false}}
 Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp),colors=CardDefaults.cardColors(containerColor=Color.White)){
  Row(Modifier.padding(16.dp),horizontalArrangement=Arrangement.spacedBy(14.dp),verticalAlignment=Alignment.CenterVertically){
   Surface(Modifier.size(76.dp),shape=RoundedCornerShape(16.dp),color=Color(0xFFF0F6EF)){
    val photo=image
    if(photo!=null)Image(photo.asImageBitmap(),"${meal.itemName} food photo",Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)),contentScale=ContentScale.Crop)
    else Box(contentAlignment=Alignment.Center){Icon(Icons.Outlined.Restaurant,"Meal photo unavailable",tint=Color(0xFF088341),modifier=Modifier.size(30.dp))}
   }
   Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)){
    Text(meal.itemName.ifBlank{"Your meal"},fontWeight=FontWeight.Bold,fontSize=16.sp,color=Color(0xFF10231B))
    Text(meal.providerName.ifBlank{"Your kitchen"},fontSize=12.sp,color=Color(0xFF66716B))
    Text("${meal.serviceDate} · ${meal.mealSlot.lowercase(Locale.ENGLISH)}",fontSize=11.sp,color=Color(0xFF66716B))
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(meal.status.lowercase(Locale.ENGLISH).replace('_',' '),fontSize=11.sp,color=Color(0xFF088341));Text(String.format(Locale.ENGLISH,"₹%.2f",meal.pricePaise/100.0),fontWeight=FontWeight.Bold,fontSize=14.sp,color=Color(0xFF10231B))}
   }
  }
 }
}

@Composable
internal fun MealComplaintScreen(meal:CustomerMealExperience,onBack:()->Unit){
 val context=LocalContext.current
 val repo=remember{SupabaseCustomerRepository(context)}
 var issue by rememberSaveable(meal.mealId){mutableStateOf("Food quality")}
 var details by rememberSaveable(meal.mealId){mutableStateOf("")}
 val pathSaver=listSaver<SnapshotStateList<String>,String>(save={it.toList()},restore={it.toMutableStateList()})
 val photos=rememberSaveable(meal.mealId,saver=pathSaver){mutableStateListOf<String>()}
 val previewPaths=rememberSaveable(meal.mealId,saver=pathSaver){mutableStateListOf<String>()}
 var complaint by remember(meal.mealId){mutableStateOf<JSONObject?>(null)}
 var error by remember{mutableStateOf<String?>(null)}
 var busy by remember{mutableStateOf(false)}
 var loading by remember{mutableStateOf(true)}
 var loadFailed by remember{mutableStateOf(false)}
 var cameraPath by rememberSaveable(meal.mealId){mutableStateOf<String?>(null)}
 val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()){ok->
  val file=cameraPath?.let{File(it)}
  if(ok&&file!=null){busy=true;repo.uploadComplaintPhoto(meal.mealId,file){path,failure->
   busy=false;error=failure;if(path!=null){photos.add(path);previewPaths.add(file.path);cameraPath=null}
  }} else {busy=false;file?.delete()}
 }
 LaunchedEffect(meal.mealId){repo.mealComplaint(meal.mealId){data,failure->loading=false;loadFailed=failure!=null;error=failure;complaint=data?.optJSONObject("complaint")}}
 BackHandler { if(!busy)onBack() }
 Scaffold(modifier=Modifier.imePadding(),containerColor=Color(0xFFF5FAF6),bottomBar={
  Surface(color=Color.White,shadowElevation=4.dp){Button(enabled=!busy&&!loading&&!loadFailed&&(complaint!=null||details.trim().length>=10),onClick={
   if(complaint!=null)onBack() else {busy=true;error=null;repo.mealComplaint(meal.mealId,issue,details.trim(),photos.toList()){data,failure->busy=false;error=failure;complaint=data?.optJSONObject("complaint");if(failure==null&&complaint==null)error="Request could not be saved. Please try again."}}
  },modifier=Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal=18.dp,vertical=12.dp).heightIn(min=52.dp),shape=RoundedCornerShape(16.dp),colors=ButtonDefaults.buttonColors(containerColor=Color(0xFF006B38))){
   Text(if(busy)"Saving…" else if(complaint!=null)"Done" else "Request support callback")
  }}
 }){padding->
  LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
   item{MealFeedbackHeader("Meal support","Let’s make it right",!busy,onBack)}
   item{MealEvidenceSummary(meal)}
   if(loading)item{LinearProgressIndicator(Modifier.fillMaxWidth(),color=Color(0xFF088341))}
   error?.let{message->item{Text(message,color=Color(0xFFB3261E),fontSize=13.sp);if(loadFailed)TextButton(enabled=!busy&&!loading,onClick={error=null;loading=true;repo.mealComplaint(meal.mealId){r,e->loading=false;loadFailed=e!=null;error=e;complaint=r?.optJSONObject("complaint")}}){Text("Retry",color=Color(0xFF006B38))}}}
   if(complaint!=null){
    item{Text("Callback request saved",fontWeight=FontWeight.Bold);Text("Reference: ${complaint!!.optString("id").take(8).uppercase()}");Text("Status: ${complaint!!.optString("status").replace('_',' ')}");Text(complaint!!.optString("details"))}
    if(complaint!!.optLong("refund_paise")>0)item{Text(String.format(Locale.ENGLISH,"Wallet credited: ₹%.2f",complaint!!.optLong("refund_paise")/100.0))}
   }else{
    item{Text("What went wrong?",fontWeight=FontWeight.Bold)}
    item{Column(verticalArrangement=Arrangement.spacedBy(4.dp)){listOf("Food quality","Wrong food","Missing items","Quantity","Late or missing delivery","Food safety","Other").chunked(2).forEach{values->Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){values.forEach{value->FilterChip(selected=issue==value,enabled=!busy&&!loading&&!loadFailed,onClick={issue=value},label={Text(value,fontSize=12.sp)},modifier=Modifier.weight(1f),shape=RoundedCornerShape(12.dp),colors=FilterChipDefaults.filterChipColors(selectedContainerColor=Color(0xFFE0F1E5),selectedLabelColor=Color(0xFF006B38)))};if(values.size==1)Spacer(Modifier.weight(1f))}}}}
    item{OutlinedTextField(value=details,onValueChange={details=it.take(1000);if(!loadFailed)error=null},enabled=!busy&&!loading&&!loadFailed,label={Text("What happened?",fontSize=14.sp)},placeholder={Text("Describe the issue so we can help",fontSize=13.sp)},supportingText={Text("At least 10 characters · ${details.trim().length}/1000",fontSize=11.sp)},textStyle=LocalTextStyle.current.copy(fontSize=14.sp),shape=RoundedCornerShape(16.dp),minLines=3,maxLines=6,modifier=Modifier.fillMaxWidth(),colors=OutlinedTextFieldDefaults.colors(focusedBorderColor=Color(0xFF088341),focusedLabelColor=Color(0xFF006B38)))}
    item{OutlinedButton(enabled=!busy&&!loading&&!loadFailed&&photos.size<3,onClick={
     runCatching{error=null;busy=true;cameraPath?.let{File(it).delete()};val folder=File(context.cacheDir,"complaint-photos").apply{mkdirs()};val file=File.createTempFile("food-",".jpg",folder);cameraPath=file.absolutePath;camera.launch(FileProvider.getUriForFile(context,context.packageName+".complaintphotos",file))}
      .onFailure{busy=false;cameraPath?.let{File(it).delete()};error="Camera could not open. Please try again."}
    },modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp),colors=ButtonDefaults.outlinedButtonColors(contentColor=Color(0xFF006B38))){Text("Take food photo · ${photos.size}/3 uploaded",fontSize=13.sp)};Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){previewPaths.forEachIndexed{i,path->val bitmap=remember(path){android.graphics.BitmapFactory.decodeFile(path,android.graphics.BitmapFactory.Options().apply{inSampleSize=8})};Column{if(bitmap!=null)Image(bitmap.asImageBitmap(),"Uploaded food evidence",Modifier.size(80.dp).clip(RoundedCornerShape(12.dp)),contentScale=ContentScale.Crop) else Text("Photo uploaded",fontSize=11.sp);TextButton(enabled=!busy,onClick={File(path).delete();previewPaths.removeAt(i);photos.removeAt(i)}){Text("Remove",fontSize=11.sp)}}}};Text("Shared privately with support and your kitchen.",fontSize=11.sp,color=Color(0xFF66716B))}
    item{Surface(color=Color(0xFFEAF4ED),shape=RoundedCornerShape(16.dp)){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){Text("We’ll call you",fontWeight=FontWeight.Bold,fontSize=14.sp,color=Color(0xFF006B38));Text("Our team will contact your registered phone number. Any wallet credit is decided after reviewing your request.",fontSize=12.sp,color=Color(0xFF66716B))}}}
   }
  }
 }
}
